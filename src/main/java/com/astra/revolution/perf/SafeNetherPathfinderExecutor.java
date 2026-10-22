package com.astra.revolution.perf;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounds nether-pathfinder JNI calls to a single thread per bot instance to prevent native memory exhaustion.
 */
public final class SafeNetherPathfinderExecutor {

    // Strictly 1 native thread per bot to prevent concurrent JNI octree allocations
    private static final ExecutorService NATIVE_PATH_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Astra-NativePathfinder");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 2); // Never starve Client Tick or Netty threads
        return t;
    });

    private static final AtomicReference<Future<?>> ACTIVE_TASK = new AtomicReference<>();

    private SafeNetherPathfinderExecutor() {}

    public static <T> CompletableFuture<T> submitExclusive(Callable<T> jniPathTask, long timeoutMs) {
        // Cancel any in-flight calculation before starting a new one
        Future<?> previous = ACTIVE_TASK.getAndSet(null);
        if (previous != null && !previous.isDone()) {
            previous.cancel(true);
        }

        CompletableFuture<T> resultFuture = new CompletableFuture<>();
        Future<?> submitted = NATIVE_PATH_EXECUTOR.submit(() -> {
            try {
                T res = jniPathTask.call();
                resultFuture.complete(res);
            } catch (Throwable t) {
                resultFuture.completeExceptionally(t);
            }
        });

        ACTIVE_TASK.set(submitted);
        return resultFuture.orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
