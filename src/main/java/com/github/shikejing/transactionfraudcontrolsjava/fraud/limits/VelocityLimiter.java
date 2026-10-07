package com.github.shikejing.transactionfraudcontrolsjava.fraud.limits;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-key (account / device) sliding-window amount and frequency limiter.
 * All state transitions for one key are serialized via striped locks so
 * concurrent transactions on the same key converge to a unique outcome:
 * no limit overshoot, no double deduction. Different keys proceed in
 * parallel, so one slow key never blocks others.
 */
@Component
public class VelocityLimiter {

    private record Usage(long atEpochMs, long amountCents) {
    }

    private static final int STRIPES = 64;

    private final Map<String, Deque<Usage>> usages = new HashMap<>();
    private final Object[] locks = new Object[STRIPES];

    public VelocityLimiter() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    private Object lockFor(String key) {
        return locks[Math.floorMod(key.hashCode(), STRIPES)];
    }

    private void evict(Deque<Usage> window, long nowEpochMs, long windowMillis) {
        long cutoff = nowEpochMs - windowMillis;
        while (!window.isEmpty() && window.peekFirst().atEpochMs() <= cutoff) {
            window.pollFirst();
        }
    }

    /**
     * Check whether admitting {@code amountCents} for {@code key} at
     * {@code nowEpochMs} stays within policy. Pure check, no mutation.
     */
    public boolean wouldExceed(String key, long amountCents, long nowEpochMs, LimitPolicy policy) {
        synchronized (lockFor(key)) {
            Deque<Usage> window = usages.get(key);
            if (window == null) {
                return false;
            }
            evict(window, nowEpochMs, policy.windowMillis());
            long sum = 0;
            for (Usage u : window) {
                sum += u.amountCents();
            }
            return sum + amountCents > policy.maxAmountCentsPerWindow()
                    || window.size() + 1 > policy.maxCountPerWindow();
        }
    }

    /**
     * Atomically check the policy and record usage when it fits. Returns
     * false (and records nothing) when admitting the amount would exceed
     * the window policy, so concurrent transactions on the same key can
     * never overshoot the limit.
     */
    public boolean tryAcquire(String key, long amountCents, long nowEpochMs, LimitPolicy policy) {
        synchronized (lockFor(key)) {
            if (wouldExceed(key, amountCents, nowEpochMs, policy)) {
                return false;
            }
            recordUsage(key, amountCents, nowEpochMs);
            return true;
        }
    }

    /**
     * Record usage for a key. Must be called exactly once per committed
     * (non-duplicate) transaction.
     */
    public void recordUsage(String key, long amountCents, long nowEpochMs) {
        synchronized (lockFor(key)) {
            usages.computeIfAbsent(key, k -> new ArrayDeque<>())
                    .addLast(new Usage(nowEpochMs, amountCents));
        }
    }

    /** Current window usage, for audit/recovery verification. */
    public long currentAmount(String key, long nowEpochMs, long windowMillis) {
        synchronized (lockFor(key)) {
            Deque<Usage> window = usages.get(key);
            if (window == null) {
                return 0;
            }
            evict(window, nowEpochMs, windowMillis);
            long sum = 0;
            for (Usage u : window) {
                sum += u.amountCents();
            }
            return sum;
        }
    }
}
