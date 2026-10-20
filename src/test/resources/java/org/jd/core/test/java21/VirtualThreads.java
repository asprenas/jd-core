package org.jd.core.test.java21;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Java 21 (JEP 444): virtual threads. API only, ExecutorService is AutoCloseable since 19
 * so it can be used in try-with-resources.
 */
public class VirtualThreads {
    public Thread start(Runnable runnable) {
        return Thread.ofVirtual().name("v-", 0).start(runnable);
    }

    public Thread unstarted(Runnable runnable) {
        return Thread.ofVirtual().unstarted(runnable);
    }

    public Thread startVirtualThread(Runnable runnable) {
        return Thread.startVirtualThread(runnable);
    }

    public int runAll(int n) throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int k = i;
                futures.add(executor.submit(() -> k * 2));
            }
            int sum = 0;
            for (Future<Integer> future : futures) {
                sum += future.get();
            }
            return sum;
        }
    }

    public boolean isVirtual() {
        return Thread.currentThread().isVirtual();
    }
}
