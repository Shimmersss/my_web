package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthService;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import com.web.backen.config.AuthConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatchmakingTrialTaskTest {
    @org.junit.jupiter.api.AfterEach void cleanup() { matchmaking.shutdown(); database.shutdown(); }
    private JdbcTemplate jdbc;
    private EmbeddedDatabase database;
    private MatchmakingTrialService trials;
    private MatchmakingService matchmaking;
    private AuthUser trialUser;

    @BeforeEach
    void setUp() throws Exception {
        database = new EmbeddedDatabaseBuilder()
                .generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        jdbc = new JdbcTemplate(database);
        AuthConfig config = new AuthConfig();
        config.setRootUsername("root");
        config.setRootPassword("root123");
        AuthService auth = new AuthService(jdbc, config);
        auth.bootstrapRoot();
        long rootId = jdbc.queryForObject("SELECT id FROM users WHERE username='root'", Long.class);
        trials = new MatchmakingTrialService(jdbc, auth,
                Clock.fixed(Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC),
                new java.security.SecureRandom());
        String code = String.valueOf(trials.createCode(rootId, "").get("code"));
        trialUser = trials.redeem(code).session().user();
        QuotaService quota = new QuotaService(jdbc);
        quota.initializeDefaults();
        matchmaking = new MatchmakingService(
                jdbc, new ObjectMapper(), null, quota,
                new TransactionTemplate(new DataSourceTransactionManager(database)), TarotTestSupport.enabled(), trials, TarotTestSupport.tarot(jdbc),
                Files.createTempDirectory("matchmaking-trial-task").toString());
    }

    @Test
    void successfulWorkerPersistsReportAndPermanentlyCompletesTrial() throws Exception {
        MatchmakingService workerService = new MatchmakingService(
                jdbc, new ObjectMapper(), TarotTestSupport::reading, new QuotaService(jdbc),
                new TransactionTemplate(new DataSourceTransactionManager(database)), TarotTestSupport.enabled(), trials, TarotTestSupport.tarot(jdbc),
                Files.createTempDirectory("matchmaking-trial-success").toString());
        workerService.init();

        String taskId = String.valueOf(workerService.createTask(trialUser, validProfile(false)).get("taskId"));
        Map<String, Object> task = Map.of();
        for (int attempt = 0; attempt < 50; attempt++) {
            task = workerService.task(trialUser, taskId);
            if ("done".equals(task.get("status")) || "error".equals(task.get("status"))) break;
            Thread.sleep(20);
        }

        assertEquals("done", task.get("status"));
        String reportId = String.valueOf(task.get("reportId"));
        assertFalse(reportId.isBlank());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM matchmaking_reports WHERE id=? AND user_id=?",
                Integer.class, reportId, trialUser.id()));
        assertEquals(MatchmakingTrialService.STATUS_COMPLETED,
                trials.accessForUser(trialUser.id()).get("status"));
        assertEquals(reportId, trials.accessForUser(trialUser.id()).get("reportId"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_transactions WHERE user_id=?", Integer.class, trialUser.id()));
        workerService.shutdown();
    }

    @Test
    void reservesExactlyOneFreeTaskAndRestoresOnlyAfterFailure() {
        Map<String, Object> queued = matchmaking.createTask(trialUser, validProfile(false));

        assertEquals(0, queued.get("credits"));
        assertEquals(MatchmakingTrialService.STATUS_RUNNING, trials.accessForUser(trialUser.id()).get("status"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_transactions WHERE user_id=?", Integer.class, trialUser.id()));
        assertEquals(409, assertThrows(AuthException.class,
                () -> matchmaking.createTask(trialUser, validProfile(false))).getStatus());

        String taskId = String.valueOf(queued.get("taskId"));

        // Trial and draw compensation are one transaction in the service, not independent retry switches.
        matchmaking.init();
        String failedId=String.valueOf(queued.get("taskId"));
        for(int i=0;i<100 && !"error".equals(matchmaking.task(trialUser,failedId).get("status"));i++) {
            try { Thread.sleep(10); } catch(InterruptedException e) { throw new RuntimeException(e); }
        }
        Map<String, Object> retry = matchmaking.createTask(trialUser, validProfile(false));
        String retryTaskId = String.valueOf(retry.get("taskId"));
        matchmaking.shutdown();
        trials.markCompleted(trialUser.id(), retryTaskId, "report-one");
        assertEquals(MatchmakingTrialService.STATUS_COMPLETED, trials.accessForUser(trialUser.id()).get("status"));
        assertEquals(409, assertThrows(AuthException.class,
                () -> matchmaking.createTask(trialUser, validProfile(false))).getStatus());
    }

    private Map<String,Object> validProfile(boolean unused) {
        Object current=TarotTestSupport.tarot(jdbc).current(trialUser.id()).get("draw");
        if(current instanceof Map<?,?> draw) return Map.of("reportVersion","tarot-reading-v1","drawId",draw.get("drawId"));
        return TarotTestSupport.body(jdbc,trialUser.id());
    }
}
