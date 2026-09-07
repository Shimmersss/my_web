package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import com.web.backen.auth.AuthUser;
import com.web.backen.auth.QuotaService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchmakingTaskConsistencyTest {
    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private QuotaService quota;
    private MatchmakingService service;
    private final AuthUser user = new AuthUser(1, "alice", "USER", 20, true);
    private final Map<String, Object> body = Map.of("city", "上海", "education", "本科", "industry", "软件", "incomeBand", "8千-1万");
    @BeforeEach void setup() throws Exception {
        db = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build();
        jdbc = new JdbcTemplate(db);
        jdbc.update("INSERT INTO users(id,username,password_hash,role,credits) VALUES(1,'alice','x','USER',20)");
        quota = new QuotaService(jdbc); quota.initializeDefaults();
        service = newService(jdbc, input -> Map.of());
    }
    @AfterEach void cleanup() { service.shutdown(); db.shutdown(); }
    private MatchmakingService newService(JdbcTemplate template, MatchmakingReportAgent agent) throws Exception {
        return new MatchmakingService(template, new ObjectMapper(), agent, quota, null, null, null, null,
                Files.createTempDirectory("matchmaking-consistency").toString());
    }
    private void awaitTerminal(String id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            Map<String, Object> state = service.task(user, id);
            if (Set.of("done", "error").contains(state.get("status")) && !Boolean.TRUE.equals(state.get("compensationPending"))) return;
            Thread.sleep(10);
        }
        fail("task did not settle");
    }
    @Test void taskInsertFailureRollsBackDebit() throws Exception {
        JdbcTemplate broken = spy(jdbc);
        doThrow(new IllegalStateException("task store unavailable")).when(broken)
                .update(eq("INSERT INTO matchmaking_tasks(id,user_id,payload) VALUES (?,?,?)"), any(), any(), any());
        service = newService(broken, input -> Map.of());
        assertThrows(IllegalStateException.class, () -> service.createTask(user, body));
        assertEquals(20, quota.balance(1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions", Integer.class));
        assertTrue(service.tasks(user).isEmpty());
    }
    @Test void concurrentSubmitsCreateAndChargeOnlyOneTask() throws Exception {
        var pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> submit = () -> { start.await(); try { service.createTask(user, body); return 200; } catch (AuthException e) { return e.getStatus(); } };
        try {
            var one = pool.submit(submit); var two = pool.submit(submit); start.countDown();
            assertEquals(Set.of(200, 429), Set.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS)));
            assertEquals(18, quota.balance(1));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tasks", Integer.class));
        } finally { pool.shutdownNow(); }
    }
    @Test void terminalSnapshotFailureRollsBackReportAndRefunds() throws Exception {
        JdbcTemplate broken = spy(jdbc);
        doAnswer(call -> {
            String payload = call.getArgument(1);
            if (payload.contains("\"status\":\"done\"")) throw new IllegalStateException("terminal save failed");
            return call.callRealMethod();
        }).when(broken).update(eq("UPDATE matchmaking_tasks SET payload=? WHERE id=?"), anyString(), anyString());
        service = newService(broken, input -> Map.of()); service.init();
        String id = (String) service.createTask(user, body).get("taskId"); awaitTerminal(id);
        assertEquals("error", service.task(user, id).get("status"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_reports", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_profiles", Integer.class));
        assertEquals(20, quota.balance(1));
    }
    @Test void deletingDuringGenerationCannotResurrectReportOrQuestionnaire() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        service = newService(jdbc, input -> {
            writing.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            finished.countDown(); return Map.of();
        });
        service.init(); service.createTask(user, body);
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            service.deleteAll(user);
            assertEquals(20, quota.balance(1));
            release.countDown(); assertTrue(finished.await(5, TimeUnit.SECONDS));
            // Waiting for a subsequent task proves the serial worker finished the cancelled one.
            String next = (String) service.createTask(user, body).get("taskId"); awaitTerminal(next);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_reports", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_profiles", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tasks", Integer.class));
        } finally { release.countDown(); }
    }
    @Test void committedReportSurvivesRestartWithoutRefundAndDeletionClearsSnapshot() throws Exception {
        service.init(); String id = (String) service.createTask(user, body).get("taskId"); awaitTerminal(id);
        String report = (String) service.task(user, id).get("reportId");
        assertEquals("done", service.task(user, id).get("status")); service.shutdown();
        service = newService(jdbc, input -> { throw new AssertionError("must not rerun"); }); service.init();
        assertEquals("done", service.task(user, id).get("status")); assertEquals(18, quota.balance(1));
        service.deleteReport(user, report);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tasks", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_profiles", Integer.class));
    }
    @Test void lostCommitAcknowledgementDoesNotRefundSavedReport() throws Exception {
        var once = new java.util.concurrent.atomic.AtomicBoolean(true);
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(db) {
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                boolean completed = jdbc.queryForList("SELECT payload FROM matchmaking_tasks", String.class).stream()
                        .anyMatch(payload -> payload.contains("\"status\":\"done\""));
                super.doCommit(status);
                if (completed && once.compareAndSet(true, false))
                    throw new org.springframework.transaction.TransactionSystemException("commit acknowledgement lost");
            }
        };
        service = new MatchmakingService(jdbc, new ObjectMapper(), input -> Map.of(), quota, null,
                new org.springframework.transaction.support.TransactionTemplate(manager), null, null,
                Files.createTempDirectory("matchmaking-commit-ack").toString());
        service.init(); String id = (String) service.createTask(user, body).get("taskId"); awaitTerminal(id);
        assertFalse(once.get());
        assertEquals("done", service.task(user, id).get("status"));
        assertEquals(18, quota.balance(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_reports", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions WHERE kind='REFUND'", Integer.class));
    }

    @Test void cancelledQueuedTaskRefundFailureKeepsDataAndRecoveryEvidence() throws Exception {
        QuotaService failing = spy(quota);
        doThrow(new IllegalStateException("refund unavailable")).when(failing).refund(anyLong(), anyString());
        service = new MatchmakingService(jdbc, new ObjectMapper(), null, failing, null, null, null, null,
                Files.createTempDirectory("matchmaking-delete-rollback").toString());
        service.createTask(user, body);
        assertThrows(IllegalStateException.class, () -> service.deleteAll(user));
        assertEquals(18, quota.balance(1));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tasks", Integer.class));
        assertEquals(1, service.tasks(user).size());
    }
}
