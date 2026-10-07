package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DecisionOutcome;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.UsageCounters;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重复提交/网络重试归并为同一次判定；并发下同账号额度收敛不穿透。
 */
class IdempotencyConcurrencyTest {

    @TempDir
    Path dir;

    private Transaction tx(String id, String account, String device, long amountCents) {
        return new Transaction(id, account, device, "payee-1", amountCents, "CNY",
                System.currentTimeMillis(), Map.of());
    }

    @Test
    void duplicateSubmissionsMergeIntoSingleDecision() {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);

        Transaction tx = tx("tx-dup", "acct-1", "dev-1", 100_00);
        DecisionOutcome first = h.engine.submit(tx, "submitter");
        DecisionOutcome retry = h.engine.submit(tx, "submitter");
        DecisionOutcome retry2 = h.engine.submit(tx, "submitter");

        assertFalse(first.duplicate());
        assertTrue(retry.duplicate());
        assertTrue(retry2.duplicate());
        // 归并到同一次判定，结论一致
        assertEquals(first.record(), retry.record());
        // 额度只累计一次
        UsageCounters counters = h.limitService.snapshot("acct-1", "dev-1",
                System.currentTimeMillis(), h.currentRules());
        assertEquals(100_00, counters.accountDailyAmountCents());
        // 拦截动作/审计只触发一次判定完成事件
        long completed = h.auditService.byTransaction("tx-dup").stream()
                .filter(e -> e.eventType().equals("decision.completed")).count();
        assertEquals(1, completed);
    }

    @Test
    void concurrentDuplicatesResolveToOneDecision() throws Exception {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);
        Transaction tx = tx("tx-race", "acct-2", "dev-2", 50_00);

        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<DecisionOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> h.engine.submit(tx, "submitter")));
        }
        DecisionRecord winner = null;
        int nonDuplicate = 0;
        for (Future<DecisionOutcome> f : futures) {
            DecisionOutcome outcome = f.get();
            if (!outcome.duplicate()) {
                nonDuplicate++;
                winner = outcome.record();
            }
        }
        pool.shutdown();
        assertEquals(1, nonDuplicate, "exactly one submission drives the decision");
        for (Future<DecisionOutcome> f : futures) {
            assertEquals(winner, f.get().record());
        }
        UsageCounters counters = h.limitService.snapshot("acct-2", "dev-2",
                System.currentTimeMillis(), h.currentRules());
        assertEquals(50_00, counters.accountDailyAmountCents());
    }

    @Test
    void concurrentSameAccountNeverExceedsDailyLimit() throws Exception {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);
        // 日限额 1,000,000 分；10 笔并发各 200,000 分 => 恰好 5 笔放行
        int threads = 10;
        long amount = 200_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();
        Map<String, DecisionType> results = new ConcurrentHashMap<>();
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String txId = "tx-quota-" + i;
            String device = "dev-q-" + i; // 不同设备，隔离设备频率约束
            tasks.add(() -> {
                DecisionOutcome outcome = h.engine.submit(
                        tx(txId, "acct-hot", device, amount), "submitter");
                results.put(txId, outcome.record().decision());
                if (outcome.record().decision() == DecisionType.ALLOW) {
                    allowed.incrementAndGet();
                } else if (outcome.record().decision() == DecisionType.BLOCK) {
                    blocked.incrementAndGet();
                }
                return null;
            });
        }
        pool.invokeAll(tasks);
        pool.shutdown();

        assertEquals(5, allowed.get(), "limit 1,000,000 / 200,000 => exactly 5 allowed");
        assertEquals(5, blocked.get());
        UsageCounters counters = h.limitService.snapshot("acct-hot", "dev-q-0",
                System.currentTimeMillis(), h.currentRules());
        assertEquals(1_000_000, counters.accountDailyAmountCents(),
                "no quota penetration under concurrency");
    }
}
