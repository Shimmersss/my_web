package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.QuotaService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MatchmakingTaskRetentionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private JdbcTemplate database() {
        var jdbc = new JdbcTemplate(new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).addScript("schema.sql").build());
        jdbc.update("INSERT INTO users(id,username,password_hash,role) VALUES(1,'alice','x','USER')");
        return jdbc;
    }
    private MatchmakingService service(JdbcTemplate jdbc, QuotaService quota, Path dir) {
        return new MatchmakingService(jdbc, mapper, null, quota, null, null, null, null, dir.toString());
    }
    private Path writeTask(Path dir, String state, Instant at, long transaction) throws Exception {
        MatchmakingTask task = new MatchmakingTask(UUID.randomUUID().toString(), 1L, transaction, 2, false, false, Map.of("city", "上海"));
        task.status = state; task.stage = state; task.createdAt = at.toString(); task.updatedAt = at.toString();
        Path file = dir.resolve(task.id + ".json"); mapper.writeValue(file.toFile(), task.snapshot()); return file;
    }
    @Test void importsLegacySnapshotsAndPrunesOnlyStaleTerminalRecords() throws Exception {
        var jdbc = database(); Path dir = Files.createTempDirectory("matchmaking-retention");
        Path stale = writeTask(dir, "done", Instant.now().minus(Duration.ofHours(25)), 0);
        Instant created = Instant.now().minusSeconds(100);
        Path recent = writeTask(dir, "done", created, 0);
        var service = service(jdbc, null, dir);
        try {
            service.init();
            assertFalse(Files.exists(stale)); assertFalse(Files.exists(recent));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM matchmaking_tasks", Integer.class));
            var snapshot = mapper.readValue(jdbc.queryForObject("SELECT payload FROM matchmaking_tasks", String.class), Map.class);
            assertEquals(created.toString(), snapshot.get("createdAt"));
        } finally { service.shutdown(); }
    }
    @Test void failedRefundSurvivesRestartAndRetentionUntilRetrySucceeds() throws Exception {
        var jdbc = database(); Path dir = Files.createTempDirectory("matchmaking-recovery");
        writeTask(dir, "queued", Instant.now().minus(Duration.ofDays(3)), 42);
        QuotaService quota = mock(QuotaService.class);
        doThrow(new IllegalStateException("database temporarily unavailable")).when(quota).refund(eq(42L), anyString());
        var first = service(jdbc, quota, dir);
        try {
            first.init(); first.pruneFinishedTasks();
            Map<?, ?> snapshot = mapper.readValue(jdbc.queryForObject("SELECT payload FROM matchmaking_tasks", String.class), Map.class);
            assertEquals(true, snapshot.get("compensationPending"));
            assertEquals(42, snapshot.get("transactionId"));
            assertEquals(Map.of(), snapshot.get("request"));
            assertTrue(snapshot.get("error").toString().contains("处理中"));
        } finally { first.shutdown(); }
        reset(quota);
        var recovered = service(jdbc, quota, dir);
        try {
            recovered.init();
            verify(quota).refund(eq(42L), anyString());
            Map<?, ?> snapshot = mapper.readValue(jdbc.queryForObject("SELECT payload FROM matchmaking_tasks", String.class), Map.class);
            assertEquals(false, snapshot.get("compensationPending"));
        } finally { recovered.shutdown(); }
    }
}
