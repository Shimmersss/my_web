package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit integration run against a local MySQL instance; only generated disposable databases are modified. */
class MatchmakingMySqlIT {
    @Test void mysqlMigrationRefundConcurrencyAndTaskRecovery() throws Exception {
        String serverUrl = Objects.requireNonNull(System.getenv("WEB_TEST_MYSQL_URL"), "Set WEB_TEST_MYSQL_URL to a local MySQL JDBC URL");
        assertTrue(serverUrl.startsWith("jdbc:mysql://127.0.0.1:") || serverUrl.startsWith("jdbc:mysql://localhost:"));
        String username = System.getenv("WEB_TEST_MYSQL_USER"), password = System.getenv("WEB_TEST_MYSQL_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(serverUrl, username, password));
        String database = "web_reliability_" + UUID.randomUUID().toString().replace("-", "");
        String other = database + "_other";
        admin.execute("CREATE DATABASE " + database);
        MatchmakingService service = null;
        try {
            admin.execute("CREATE DATABASE " + other);
            String base = serverUrl.substring(0, serverUrl.indexOf('/', "jdbc:mysql://".length()));
            var source = new DriverManagerDataSource(base + "/" + database + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8", username, password);
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
            var jdbc = new JdbcTemplate(source);
            // A second visible schema with the same names used to cause false positives/multiple-row errors.
            admin.execute("CREATE TABLE " + other + ".matchmaking_reports(report_payload TEXT)");
            admin.execute("CREATE TABLE " + other + ".matchmaking_trial_codes(code_plain VARCHAR(64))");
            jdbc.execute("ALTER TABLE matchmaking_trial_codes DROP COLUMN code_plain");
            jdbc.execute("ALTER TABLE matchmaking_reports MODIFY COLUMN report_payload TEXT NOT NULL");
            new MatchmakingSchemaMigration(jdbc).addAccountOwnedPayloadColumns();
            assertEquals("mediumtext", jdbc.queryForObject("SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='matchmaking_reports' AND COLUMN_NAME='report_payload'", String.class));
            jdbc.queryForList("SELECT code_plain FROM matchmaking_trial_codes");
            jdbc.update("INSERT INTO users(id,username,password_hash,role,credits) VALUES(1,'test','x','USER',20)");
            var quota = new QuotaService(jdbc); quota.initializeDefaults();
            var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            long spend = tx.execute(status -> quota.spend(1, 3, "TEST", "parallel-refund", "test"));
            var pool = Executors.newFixedThreadPool(6); var start = new CountDownLatch(1);
            try {
                var futures = new ArrayList<Future<?>>();
                for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    tx.executeWithoutResult(status -> quota.refund(spend, "parallel retry"));
                }));
                start.countDown(); for (var future : futures) future.get(15, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            assertEquals(20, quota.balance(1));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions WHERE kind='REFUND'", Integer.class));
            var user = new AuthUser(1, "test", "USER", 20, true);
            String directory = Files.createTempDirectory("web-mysql-recovery").toString();
            service = new MatchmakingService(jdbc, new ObjectMapper(), input -> Map.of(), quota, null, tx, null, null, directory);
            service.init();
            String task = (String) service.createTask(user, Map.of("city", "上海", "education", "本科", "industry", "软件", "incomeBand", "8千-1万")).get("taskId");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!"done".equals(service.task(user, task).get("status")) && System.nanoTime() < until) Thread.sleep(20);
            assertEquals("done", service.task(user, task).get("status"));
            String reportId = (String) service.task(user, task).get("reportId"); service.shutdown();
            service = new MatchmakingService(jdbc, new ObjectMapper(), null, quota, null, tx, null, null, directory);
            service.init(); assertEquals("done", service.task(user, task).get("status")); assertEquals(18, quota.balance(1));
            service.deleteReport(user, reportId);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_profiles", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tasks", Integer.class));
        } finally {
            if (service != null) service.shutdown();
            admin.execute("DROP DATABASE IF EXISTS " + other);
            admin.execute("DROP DATABASE IF EXISTS " + database);
        }
    }
}
