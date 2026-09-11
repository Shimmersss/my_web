package com.web.backen.runtime;

import com.web.backen.auth.AuthException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Single-process admission and resource budget. It is deliberately not a distributed queue. */
@Component
public class TaskCoordinator {
    private final Path lock;
    private final Semaphore heavy = new Semaphore(1, true);
    private final Semaphore network = new Semaphore(2, true);
    private final AtomicInteger admitting = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();
    private final Map<String, Supplier<Map<String, Object>>> modules = new ConcurrentHashMap<>();

    @Autowired public TaskCoordinator(RuntimePaths paths) { this(paths.deploymentLock()); }
    public TaskCoordinator(Path lock) { this.lock = lock; }
    public static TaskCoordinator local() { return new TaskCoordinator(new RuntimePaths("")); }

    public synchronized Lease admit() {
        if (Files.exists(lock)) throw new AuthException(503, "服务维护中，暂不接收新任务，请稍后再试");
        admitting.incrementAndGet();
        return new Lease(admitting::decrementAndGet);
    }
    public Lease heavy(BooleanSupplier cancelled) throws InterruptedException { return acquire(heavy, cancelled); }
    public Lease network() throws InterruptedException { return acquire(network, () -> false); }
    private Lease acquire(Semaphore semaphore, BooleanSupplier cancelled) throws InterruptedException {
        waiting.incrementAndGet();
        try {
            while (!semaphore.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw new InterruptedException("任务已取消");
            }
            if (cancelled.getAsBoolean()) { semaphore.release(); throw new InterruptedException("任务已取消"); }
            return new Lease(semaphore::release);
        } finally { waiting.decrementAndGet(); }
    }
    public synchronized void pause() throws IOException {
        Files.createDirectories(lock.getParent());
        if (!Files.exists(lock)) Files.writeString(lock, "admin\n", StandardOpenOption.CREATE_NEW);
    }
    public synchronized void resume() throws IOException {
        if (!Files.exists(lock)) return;
        if (!"admin\n".equals(Files.readString(lock))) throw new AuthException(409, "发布流程正在维护，请在发布流程结束后恢复接单");
        Files.delete(lock);
    }
    public void register(String name, Supplier<Map<String, Object>> status) { modules.put(name, status); }
    public Map<String, Object> snapshot() {
        Map<String, Object> details = new LinkedHashMap<>();
        modules.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            try { details.put(entry.getKey(), entry.getValue().get()); }
            catch (RuntimeException e) { details.put(entry.getKey(), Map.of("available", false)); }
        });
        return Map.of("accepting", !Files.exists(lock), "admitting", admitting.get(),
                "heavyActive", 1 - heavy.availablePermits(), "networkActive", 2 - network.availablePermits(),
                "waiting", waiting.get(), "modules", details);
    }
    public static final class Lease implements AutoCloseable {
        private Runnable release;
        private Lease(Runnable release) { this.release = release; }
        @Override public synchronized void close() { if (release != null) { release.run(); release = null; } }
    }
}
