package com.example.agentdemo.config;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

final class InMemoryApiRateLimiter implements ApiRateLimiter {

    static final long WINDOW_MS = 60_000L;

    private static final int MAX_TRACKED_WINDOWS = 10_000;

    private final int requestsPerMinute;
    private final LongSupplier currentTimeMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    InMemoryApiRateLimiter(int requestsPerMinute) {
        this(requestsPerMinute, System::currentTimeMillis);
    }

    InMemoryApiRateLimiter(int requestsPerMinute, LongSupplier currentTimeMillis) {
        this.requestsPerMinute = requestsPerMinute;
        this.currentTimeMillis = currentTimeMillis;
    }

    @Override
    public boolean allow(String key) {
        if (requestsPerMinute <= 0) {
            return true;
        }
        long currentWindow = currentTimeMillis.getAsLong() / WINDOW_MS;
        Window window = windows.compute(key, (ignored, existing) -> {
            if (existing == null || existing.window() != currentWindow) {
                return new Window(currentWindow, 1);
            }
            existing.increment();
            return existing;
        });
        cleanupOldWindows(currentWindow);
        return window.count() <= requestsPerMinute;
    }

    private void cleanupOldWindows(long currentWindow) {
        if (windows.size() <= MAX_TRACKED_WINDOWS) {
            return;
        }
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().window() < currentWindow) {
                iterator.remove();
            }
        }
    }

    private static final class Window {

        private final long window;
        private int count;

        private Window(long window, int count) {
            this.window = window;
            this.count = count;
        }

        private long window() {
            return window;
        }

        private int count() {
            return count;
        }

        private void increment() {
            count++;
        }

    }

}
