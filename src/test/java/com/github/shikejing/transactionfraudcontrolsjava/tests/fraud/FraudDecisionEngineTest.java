package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.config.FraudProperties;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.RuleHit;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.FraudDecisionEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.SubmissionResult;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.VelocityLimiter;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence.JournalStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.RiskScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FraudDecisionEngineTest {

    private FraudProperties properties;
    private VelocityLimiter limiter;
    private FraudDecisionEngine engine;
    private IdempotencyStore idempotencyStore;

    @BeforeEach
    void setUp() {
        properties = new FraudProperties();
        properties.setAccountMaxAmountCentsPerWindow(100_000);
        properties.setAccountMaxCountPerWindow(5);
        properties.setAccountWindowMillis(60_000);
        properties.setDeviceMaxAmountCentsPerWindow(1_000_000);
        properties.setDeviceMaxCountPerWindow(100);
        limiter = new VelocityLimiter();
        idempotencyStore = new IdempotencyStore();
        engine = new FraudDecisionEngine(idempotencyStore, new RuleRegistry(), new ListRegistry(),
                new RiskScorer(), limiter, new AuditLog(), new JournalStore(properties), properties);
    }

    private Transaction tx(String id, String account, long amountCents) {
        return new Transaction(id, account, "dev-" + account, "payee-1",
                amountCents, "CNY", System.currentTimeMillis(), Map.of());
    }

    @Test
    void duplicateSubmissionsFoldIntoSingleDecision() {
        Transaction tx = tx("tx-1", "acct-1", 10_000);
        SubmissionResult first = engine.submit(tx);
        SubmissionResult retry = engine.submit(tx);
        SubmissionResult retry2 = engine.submit(tx);

        assertFalse(first.duplicate());
        assertTrue(retry.duplicate());
        assertTrue(retry2.duplicate());
        assertEquals(first.record(), retry.record());
        // Limit accumulated exactly once despite three submissions.
        assertEquals(10_000, limiter.currentAmount("account:acct-1",
                System.currentTimeMillis(), properties.getAccountWindowMillis()));
    }

    @Test
    void concurrentDuplicateSubmissionsDecideExactlyOnce() throws Exception {
        Transaction tx = tx("tx-race", "acct-race", 20_000);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SubmissionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return engine.submit(tx);
            }));
        }
        start.countDown();
        List<SubmissionResult> results = new ArrayList<>();
        for (Future<SubmissionResult> f : futures) {
            results.add(f.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();

        DecisionRecord canonical = results.get(0).record();
        for (SubmissionResult r : results) {
            assertEquals(canonical, r.record());
        }
        // Exactly one decision recorded; usage accumulated exactly once.
        assertEquals(20_000, limiter.currentAmount("account:acct-race",
                System.currentTimeMillis(), properties.getAccountWindowMillis()));
    }

    @Test
    void noLimitOvershootUnderConcurrency() throws Exception {
        // Window allows 5 * 10_000 = 50_000 for the account; 20 concurrent
        // transactions of 10_000 each must yield at most 5 ALLOW.
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SubmissionResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String id = "tx-limit-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                return engine.submit(tx(id, "acct-hot", 10_000));
            }));
        }
        start.countDown();
        long allowed = 0;
        for (Future<SubmissionResult> f : futures) {
            if (f.get(10, TimeUnit.SECONDS).record().decision() == DecisionType.ALLOW) {
                allowed++;
            }
        }
        pool.shutdown();

        assertEquals(5, allowed);
        assertEquals(50_000, limiter.currentAmount("account:acct-hot",
                System.currentTimeMillis(), properties.getAccountWindowMillis()));
    }

    @Test
    void scoringIsDeterministicAndTraceable() {
        Map<String, Double> signals = Map.of(
                "amountCents", 600_000.0,
                "deviceFirstSeenDays", 0.0,   // zero, observed: rule fires (0 >= 1? no)
                "accountTxCount1h", 12.0);
        Transaction tx = new Transaction("tx-score", "acct-s", "dev-s", "payee-s",
                600_000, "CNY", System.currentTimeMillis(), signals);

        DecisionRecord r1 = engine.submit(tx).record();
        // Same signals + same rule version -> identical per-rule results.
        RiskScorer scorer = new RiskScorer();
        var s1 = scorer.score(tx, new RuleRegistry().current());
        var s2 = scorer.score(tx, new RuleRegistry().current());
        assertEquals(s1, s2);

        // Every hit traces rule id, version, observed value and contribution.
        for (RuleHit hit : r1.ruleHits()) {
            assertNotNull(hit.ruleId());
            assertEquals(r1.ruleVersion(), hit.ruleVersion());
        }
        // R-HIGH-AMOUNT fired (600_000 >= 500_000, weight 40) and
        // R-VELOCITY-1H fired (12 >= 10, weight 20): score 60 -> BLOCK.
        assertEquals(60.0, r1.score());
        assertEquals(DecisionType.BLOCK, r1.decision());
        assertTrue(r1.reasons().stream().anyMatch(r -> r.contains("block threshold")));
    }

    @Test
    void missingSignalDiffersFromZeroSignal() {
        // deviceFirstSeenDays threshold is 1: zero value does NOT fire
        // (0 >= 1 is false) but is recorded as present; missing is absent.
        Transaction withZero = new Transaction("tx-zero", "acct-z", "dev-z", "payee-z",
                100, "CNY", System.currentTimeMillis(), Map.of("deviceFirstSeenDays", 0.0));
        Transaction withMissing = new Transaction("tx-missing", "acct-m", "dev-m", "payee-m",
                100, "CNY", System.currentTimeMillis(), Map.of());

        DecisionRecord rz = engine.submit(withZero).record();
        DecisionRecord rm = engine.submit(withMissing).record();

        RuleHit zeroHit = rz.ruleHits().stream()
                .filter(h -> h.ruleId().equals("R-NEW-DEVICE")).findFirst().orElseThrow();
        RuleHit missingHit = rm.ruleHits().stream()
                .filter(h -> h.ruleId().equals("R-NEW-DEVICE")).findFirst().orElseThrow();

        assertTrue(zeroHit.signalPresent());
        assertEquals(0.0, zeroHit.observedValue());
        assertFalse(zeroHit.matched());

        assertFalse(missingHit.signalPresent());
        assertEquals(null, missingHit.observedValue());
        assertFalse(missingHit.matched());
    }

    @Test
    void decisionsAreExplainable() {
        DecisionRecord allowed = engine.submit(tx("tx-ok", "acct-ok", 1_000)).record();
        assertEquals(DecisionType.ALLOW, allowed.decision());
        assertFalse(allowed.reasons().isEmpty());
        assertEquals("v1", allowed.ruleVersion());
    }
}
