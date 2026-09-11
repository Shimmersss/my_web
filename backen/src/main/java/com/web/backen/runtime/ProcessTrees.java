package com.web.backen.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Capture descendants before destroying their parent so reparented children remain reclaimable. */
public final class ProcessTrees {
    private ProcessTrees() {}
    public static void terminate(Process process) {
        if (process == null) return;
        List<ProcessHandle> handles = new ArrayList<>(process.descendants().toList());
        handles.sort(Comparator.comparingLong(ProcessHandle::pid).reversed());
        handles.add(process.toHandle());
        handles.forEach(ProcessHandle::destroy);
        boolean interrupted = false;
        long until = System.nanoTime() + Duration.ofMillis(500).toNanos();
        while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < until) {
            try { Thread.sleep(20); } catch (InterruptedException e) { interrupted = true; break; }
        }
        handles.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (interrupted) Thread.currentThread().interrupt();
    }
}
