package com.github.shikejing.transactionfraudcontrolsjava.fraud.limits;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.Journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 额度/频率服务：账号日累计额度 + 设备滑动窗口频率。
 *
 * 一致性设计：
 * - 账号与设备分别使用分条锁，固定“先账号后设备”加锁顺序，避免死锁；
 * - 预留按 transactionId 记录在案，重复 acquire 命中已有预留直接返回成功（幂等），
 *   重复提交与中断重放都不会重复扣减；
 * - 所有变动先写日志再改内存，重启重放后计数与宕机前一致。
 */
public class DefaultLimitService implements LimitService {

    static final String EVENT_ACQUIRE = "limit-acquire";
    static final String EVENT_RELEASE = "limit-release";

    private static final int STRIPES = 64;
    private static final long DAY_MS = 86_400_000L;

    private final Journal journal;
    private final ObjectMapper mapper;
    private final ReentrantLock[] accountLocks = new ReentrantLock[STRIPES];
    private final ReentrantLock[] deviceLocks = new ReentrantLock[STRIPES];

    /** transactionId -> 已生效的预留 */
    private final Map<String, LimitEvent> reservations = new HashMap<>();
    /** accountId -> (dayBucket -> usedCents) */
    private final Map<String, Map<Long, Long>> accountDaily = new HashMap<>();
    /** deviceId -> 窗口内交易时间戳 */
    private final Map<String, Deque<Long>> deviceWindow = new HashMap<>();

    public DefaultLimitService(Journal journal, ObjectMapper mapper) {
        this.journal = journal;
        this.mapper = mapper;
        for (int i = 0; i < STRIPES; i++) {
            accountLocks[i] = new ReentrantLock();
            deviceLocks[i] = new ReentrantLock();
        }
    }

    /** 重启恢复：重放日志重建预留与计数。 */
    public void recover() {
        for (Journal.Entry entry : journal.replay()) {
            try {
                if (EVENT_ACQUIRE.equals(entry.type())) {
                    LimitEvent e = mapper.readValue(entry.payloadJson(), LimitEvent.class);
                    applyAcquire(e);
                } else if (EVENT_RELEASE.equals(entry.type())) {
                    LimitEvent e = mapper.readValue(entry.payloadJson(), LimitEvent.class);
                    applyRelease(e.transactionId());
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public LimitDecision tryAcquire(String transactionId, String accountId, String deviceId,
                                    long amountCents, long nowEpochMs, RuleSet limits) {
        ReentrantLock accountLock = accountLocks[stripe(accountId)];
        ReentrantLock deviceLock = deviceLocks[stripe(deviceId)];
        accountLock.lock();
        try {
            deviceLock.lock();
            try {
                LimitEvent existing = reservations.get(transactionId);
                if (existing != null) {
                    return new LimitDecision(true, "idempotent replay of reservation " + transactionId);
                }
                long day = dayBucket(nowEpochMs);
                long used = accountDaily.getOrDefault(accountId, Map.of()).getOrDefault(day, 0L);
                if (used + amountCents > limits.accountDailyLimitCents()) {
                    return new LimitDecision(false,
                            "account daily limit exceeded: used=" + used + " amount=" + amountCents
                                    + " limit=" + limits.accountDailyLimitCents());
                }
                Deque<Long> window = deviceWindow.computeIfAbsent(deviceId, k -> new ArrayDeque<>());
                evict(window, nowEpochMs, limits.deviceWindowMs());
                if (window.size() + 1 > limits.deviceMaxTxPerWindow()) {
                    return new LimitDecision(false,
                            "device frequency limit exceeded: count=" + window.size()
                                    + " limit=" + limits.deviceMaxTxPerWindow());
                }
                LimitEvent event = new LimitEvent(transactionId, accountId, deviceId,
                        amountCents, nowEpochMs);
                append(EVENT_ACQUIRE, event);
                applyAcquire(event);
                return new LimitDecision(true, "acquired");
            } finally {
                deviceLock.unlock();
            }
        } finally {
            accountLock.unlock();
        }
    }

    @Override
    public void release(String transactionId, long nowEpochMs) {
        LimitEvent existing;
        synchronized (this) {
            existing = reservations.get(transactionId);
        }
        if (existing == null) {
            return; // 幂等：无预留可还
        }
        ReentrantLock accountLock = accountLocks[stripe(existing.accountId())];
        ReentrantLock deviceLock = deviceLocks[stripe(existing.deviceId())];
        accountLock.lock();
        try {
            deviceLock.lock();
            try {
                if (!reservations.containsKey(transactionId)) {
                    return;
                }
                append(EVENT_RELEASE, existing);
                applyRelease(transactionId);
            } finally {
                deviceLock.unlock();
            }
        } finally {
            accountLock.unlock();
        }
    }

    @Override
    public UsageCounters snapshot(String accountId, String deviceId, long nowEpochMs, RuleSet limits) {
        long day = dayBucket(nowEpochMs);
        long used = accountDaily.getOrDefault(accountId, Map.of()).getOrDefault(day, 0L);
        Deque<Long> window = deviceWindow.getOrDefault(deviceId, new ArrayDeque<>());
        evict(window, nowEpochMs, limits.deviceWindowMs());
        return new UsageCounters(used, window.size());
    }

    private void applyAcquire(LimitEvent e) {
        reservations.put(e.transactionId(), e);
        long day = dayBucket(e.atEpochMs());
        accountDaily.computeIfAbsent(e.accountId(), k -> new HashMap<>())
                .merge(day, e.amountCents(), Long::sum);
        deviceWindow.computeIfAbsent(e.deviceId(), k -> new ArrayDeque<>()).addLast(e.atEpochMs());
    }

    private void applyRelease(String transactionId) {
        LimitEvent e = reservations.remove(transactionId);
        if (e == null) {
            return;
        }
        long day = dayBucket(e.atEpochMs());
        Map<Long, Long> days = accountDaily.get(e.accountId());
        if (days != null) {
            days.merge(day, -e.amountCents(), Long::sum);
        }
        Deque<Long> window = deviceWindow.get(e.deviceId());
        if (window != null && !window.isEmpty()) {
            window.pollLast();
        }
    }

    private void append(String type, LimitEvent event) {
        try {
            journal.append(type, mapper.writeValueAsString(event));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void evict(Deque<Long> window, long nowEpochMs, long windowMs) {
        long cutoff = nowEpochMs - windowMs;
        while (!window.isEmpty() && window.peekFirst() < cutoff) {
            window.pollFirst();
        }
    }

    private static long dayBucket(long epochMs) {
        return Math.floorDiv(epochMs, DAY_MS);
    }

    private static int stripe(String key) {
        return (key.hashCode() & 0x7fffffff) % STRIPES;
    }

    public record LimitEvent(String transactionId, String accountId, String deviceId,
                             long amountCents, long atEpochMs) {
    }
}
