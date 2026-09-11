package com.web.backen.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class WorkerShutdown {
    private WorkerShutdown() {}
    public static void await(ExecutorService executor) {
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("后台任务尚未停止，必须保留运行目录并检查进程");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
