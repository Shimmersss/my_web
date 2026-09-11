package com.web.backen.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.web.backen.auth.AuthException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TaskInfrastructureTest {
    @TempDir Path temp;
    @Test void pauseRetainsInflightAdmissionsAndCannotRemoveDeploymentOwnedLock() throws Exception {
        Path lock = temp.resolve("maintenance");
        TaskCoordinator coordinator = new TaskCoordinator(lock);
        try (var admission = coordinator.admit()) {
            coordinator.pause();
            assertEquals(1, coordinator.snapshot().get("admitting"));
            assertEquals(503, assertThrows(AuthException.class, coordinator::admit).getStatus());
        }
        assertEquals(0, coordinator.snapshot().get("admitting"));
        coordinator.resume();
        Files.writeString(lock, "deployment\n");
        assertThrows(AuthException.class, coordinator::resume);
        assertTrue(Files.exists(lock));
    }
    @Test void heavyWorkIsSerializedAndCancelledWaiterNeverAcquiresCapacity() throws Exception {
        TaskCoordinator coordinator = new TaskCoordinator(temp.resolve("lock"));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicBoolean cancel = new AtomicBoolean();
        try (var first = coordinator.heavy(() -> false)) {
            Future<?> waiting = pool.submit(() -> {
                assertThrows(InterruptedException.class, () -> coordinator.heavy(cancel::get));
            });
            cancel.set(true);
            waiting.get(2, TimeUnit.SECONDS);
            assertEquals(1, coordinator.snapshot().get("heavyActive"));
        } finally { pool.shutdownNow(); }
        assertEquals(0, coordinator.snapshot().get("waiting"));
        try (var next = coordinator.heavy(() -> false)) { assertEquals(1, coordinator.snapshot().get("heavyActive")); }
        assertEquals(0, coordinator.snapshot().get("heavyActive"));
    }
    @Test void failedReplacementPreservesReadableOldSnapshotAndBecomesRetryable() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); AtomicBoolean fail = new AtomicBoolean();
        AtomicTaskStore store = new AtomicTaskStore(mapper) {
            @Override protected void replace(Path source, Path target) throws IOException {
                if (fail.get()) throw new IOException("injected disk failure");
                super.replace(source, target);
            }
        };
        Path target = temp.resolve("task.json");
        store.write(target, Map.of("status", "creating")); fail.set(true);
        assertThrows(UncheckedIOException.class, () -> store.write(target, Map.of("status", "failed")));
        assertEquals("creating", mapper.readTree(target.toFile()).path("status").asText());
        assertTrue(store.isDirty(target)); fail.set(false);
        store.write(target, Map.of("status", "failed"));
        assertFalse(store.isDirty(target));
        try (var files = Files.list(temp)) { assertEquals(1, files.count()); }
    }
    @Test void explicitRootKeepsLegacyStoragePathsAndAbsoluteOverrides() {
        RuntimePaths paths = new RuntimePaths(temp.toString());
        assertEquals(temp.resolve(".run/translation-tasks"), paths.resolve("../.run/translation-tasks"));
        assertEquals(temp.resolve("custom"), paths.resolve(temp.resolve("custom").toString()));
    }
    @Test void interruptedWorkerCanPersistCancellationWithoutLosingItsInterrupt() throws Exception {
        Path target = temp.resolve("cancelled.json");
        Thread.currentThread().interrupt();
        try {
            new AtomicTaskStore(new ObjectMapper()).write(target, Map.of("status", "cancelled"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals("cancelled", new ObjectMapper().readTree(target.toFile()).path("status").asText());
    }
    @Test void processTreeTerminationReclaimsAChildThatIgnoresGracefulSignals() throws Exception {
        Process parent = new ProcessBuilder("/bin/sh", "-c", "trap '' TERM; sleep 60 & echo $!; wait").start();
        long child = Long.parseLong(new java.io.BufferedReader(new java.io.InputStreamReader(parent.getInputStream())).readLine());
        try {
            ProcessTrees.terminate(parent);
            parent.waitFor(3, TimeUnit.SECONDS);
            assertFalse(parent.isAlive());
            var handle = ProcessHandle.of(child);
            if (handle.isPresent()) {
                try { handle.get().onExit().get(3, TimeUnit.SECONDS); } catch (ExecutionException ignored) { }
                assertFalse(handle.get().isAlive());
            }
        } finally { ProcessTrees.terminate(parent); }
    }
}
