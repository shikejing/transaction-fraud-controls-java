package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.config.FraudProperties;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.FraudDecisionEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.VelocityLimiter;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListEntry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListKind;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListKeyType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence.JournalStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.RiskScorer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListRegistryTest {

    private FraudDecisionEngine newEngine(ListRegistry lists) {
        FraudProperties props = new FraudProperties();
        return new FraudDecisionEngine(new IdempotencyStore(), new RuleRegistry(), lists,
                new RiskScorer(), new VelocityLimiter(), new AuditLog(),
                new JournalStore(props), props);
    }

    private Transaction tx(String id, String account) {
        return new Transaction(id, account, "dev-l", "payee-l", 100, "CNY",
                System.currentTimeMillis(), Map.of());
    }

    @Test
    void blockListBlocksAndWatchListReviews() {
        ListRegistry lists = new ListRegistry();
        FraudDecisionEngine engine = newEngine(lists);
        long now = System.currentTimeMillis();

        lists.upsert(new ListEntry(ListKeyType.ACCOUNT, "bad-acct", ListKind.BLOCK,
                "fraud-team", 10, 0));
        lists.upsert(new ListEntry(ListKeyType.ACCOUNT, "watch-acct", ListKind.WATCH,
                "aml-team", 10, now + 60_000));

        DecisionRecord blocked = engine.submit(tx("tx-b", "bad-acct")).record();
        assertEquals(DecisionType.BLOCK, blocked.decision());
        assertTrue(blocked.reasons().stream().anyMatch(r -> r.contains("block list")));
        assertTrue(blocked.listHits().stream().anyMatch(h -> h.contains("fraud-team")));

        DecisionRecord watched = engine.submit(tx("tx-w", "watch-acct")).record();
        assertEquals(DecisionType.REVIEW, watched.decision());
        assertTrue(watched.reasons().stream().anyMatch(r -> r.contains("watch list")));

        DecisionRecord clean = engine.submit(tx("tx-c", "clean-acct")).record();
        assertEquals(DecisionType.ALLOW, clean.decision());
    }

    @Test
    void higherPrioritySourceWinsAndExpiryApplies() {
        ListRegistry lists = new ListRegistry();
        long now = System.currentTimeMillis();
        lists.upsert(new ListEntry(ListKeyType.DEVICE, "dev-x", ListKind.BLOCK,
                "low-prio-feed", 1, 0));
        lists.upsert(new ListEntry(ListKeyType.DEVICE, "dev-x", ListKind.BLOCK,
                "high-prio-feed", 9, 0));

        var match = lists.lookup(ListKeyType.DEVICE, "dev-x", now).orElseThrow();
        assertEquals("high-prio-feed", match.entry().source());

        // Expired entries are invisible.
        lists.upsert(new ListEntry(ListKeyType.ACCOUNT, "exp-acct", ListKind.BLOCK,
                "feed", 5, now - 1));
        assertEquals(Optional.empty(),
                lists.lookup(ListKeyType.ACCOUNT, "exp-acct", now));
    }

    @Test
    void listChangesAreAtomicForConcurrentReaders() throws Exception {
        ListRegistry lists = new ListRegistry();
        int writers = 4;
        int readers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger inconsistent = new AtomicInteger();

        for (int w = 0; w < writers; w++) {
            int sourceNo = w;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                for (int i = 0; i < 200; i++) {
                    // Each writer replaces ALL entries with its own generation.
                    lists.upsert(new ListEntry(ListKeyType.ACCOUNT, "k" + i,
                            ListKind.BLOCK, "src-" + sourceNo, sourceNo, 0));
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    return;
                }
                long now = System.currentTimeMillis();
                for (int i = 0; i < 2_000; i++) {
                    // A lookup must always return a well-formed single entry,
                    // never a torn/half-updated view.
                    lists.lookup(ListKeyType.ACCOUNT, "k" + (i % 200), now)
                            .ifPresent(m -> {
                                if (m.entry().priority() < 0 || m.entry().source() == null) {
                                    inconsistent.incrementAndGet();
                                }
                            });
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, inconsistent.get());
    }
}
