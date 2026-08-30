package com.web.backen.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Terminal task snapshots are progress artifacts kept for a day; queued/running ones must always survive for refunds. */
class MatchmakingTaskRetentionTest {

    @Test void recoveryPrunesStaleTerminalSnapshotsAndKeepsRecentOnes() throws Exception {
        Path dir = Files.createTempDirectory("matchmaking-retention");
        Path stale = writeTask(dir, "done", Instant.now().minus(Duration.ofHours(25)));
        Path recent = writeTask(dir, "done", Instant.now());
        MatchmakingService service = new MatchmakingService(
                null, new ObjectMapper(), null, null, null, null, dir.toString());
        service.init();
        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(recent));
    }

    @Test void recoveryNeverDeletesInterruptedSnapshotsRegardlessOfAge() throws Exception {
        Path dir = Files.createTempDirectory("matchmaking-recovery");
        Path queued = writeTask(dir, "queued", Instant.now().minus(Duration.ofDays(3)));
        MatchmakingService service = new MatchmakingService(
                null, new ObjectMapper(), null, null, null, null, dir.toString());
        service.init();
        assertTrue(Files.exists(queued));
        Map<?, ?> saved = new ObjectMapper().readValue(queued.toFile(), Map.class);
        assertEquals("error", saved.get("status"));
    }

    /** Recovery persists by task id, so the snapshot file name must match its id. */
    private Path writeTask(Path dir, String status, Instant updatedAt) throws Exception {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", UUID.randomUUID().toString());
        snapshot.put("userId", 1L);
        snapshot.put("transactionId", 0L);
        snapshot.put("credits", 2);
        snapshot.put("includePartnerImage", false);
        snapshot.put("request", Map.of("city", "上海"));
        snapshot.put("status", status);
        snapshot.put("stage", status);
        snapshot.put("reportId", "");
        snapshot.put("error", "");
        snapshot.put("createdAt", updatedAt.toString());
        snapshot.put("updatedAt", updatedAt.toString());
        Path file = dir.resolve(snapshot.get("id") + ".json");
        new ObjectMapper().writeValue(file.toFile(), snapshot);
        return file;
    }
}
