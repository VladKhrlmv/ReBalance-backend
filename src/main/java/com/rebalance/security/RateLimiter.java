package com.rebalance.security;

import com.rebalance.exception.RebalanceErrorType;
import com.rebalance.exception.RebalanceException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory sliding-window limiter. Good enough for a single backend instance; counters reset on restart.
 */
@Component
public class RateLimiter {
    private static final Duration MAX_WINDOW = Duration.ofHours(1);

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    /**
     * Records a hit for the key and throws 429 if the key already reached the limit within the window.
     */
    public void hit(String key, int limit, Duration window) {
        if (isLimited(key, limit, window)) {
            throw new RebalanceException(RebalanceErrorType.RB_004);
        }
        record(key);
    }

    /** True if the key has at least {@code limit} hits within the window (doesn't record anything). */
    public boolean isLimited(String key, int limit, Duration window) {
        Deque<Long> timestamps = hits.get(key);
        if (timestamps == null) {
            return false;
        }
        long threshold = System.currentTimeMillis() - window.toMillis();
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= threshold) {
                timestamps.pollFirst();
            }
            return timestamps.size() >= limit;
        }
    }

    public void record(String key) {
        Deque<Long> timestamps = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            timestamps.addLast(System.currentTimeMillis());
        }
    }

    public void reset(String key) {
        hits.remove(key);
    }

    @Scheduled(fixedDelay = 10 * 60 * 1000)
    public void evictStale() {
        long threshold = System.currentTimeMillis() - MAX_WINDOW.toMillis();
        hits.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                Long last = entry.getValue().peekLast();
                return last == null || last <= threshold;
            }
        });
    }
}
