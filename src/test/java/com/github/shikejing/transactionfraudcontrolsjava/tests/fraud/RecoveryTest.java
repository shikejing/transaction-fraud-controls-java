package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AuthorizationService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.config.FraudProperties;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Operator;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.FraudDecisionEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.SubmissionResult;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.VelocityLimiter;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence.JournalStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.review.ReviewService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.RiskScorer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTest {

    @TempDir
    Path journalDir;

    private static class World {
        final FraudProperties props = new FraudProperties();
        final IdempotencyStore idempotency = new IdempotencyStore();
        final AuditLog audit = new AuditLog();
        final VelocityLimiter limiter = new VelocityLimiter();
        final JournalStore journal;
        final ReviewService reviews;
        final FraudDecisionEngine engine;

        World(Path dir) {
            props.setJournalDir(dir.toString());
            props.setAccountMaxAmountCentsPerWindow(100_000);
            props.setAccountMaxCountPerWindow(5);
            journal = new JournalStore(props);
            reviews = new ReviewService(new AuthorizationService(), idempotency, audit, journal);
            engine = new FraudDecisionEngine(idempotency, new RuleRegistry(), new ListRegistry(),
                    new RiskScorer(), limiter, audit, journal, props);
        }

        void recover() {
            journal.recover(idempotency, reviews, audit, limiter);
        }
    }

    private static Transaction tx(String id, String account, long amountCents) {
        return new Transaction(id, account, "dev-" + account, "payee-r", amountCents, "CNY",
                System.currentTimeMillis(), Map.of());
    }

    @Test
    void restartRestoresDecisionsLimitsAndReviewsWithoutReexecution() {
        // --- first process lifetime ---
        World first = new World(journalDir);
        first.engine.submit(tx("tx-a", "acct-1", 30_000));
        first.engine.submit(tx("tx-b", "acct-1", 30_000));
        // Duplicate before crash: must not double-count.
        first.engine.submit(tx("tx-a", "acct-1", 30_000));
        // A blocked transaction with an appeal resolved before crash.
        Transaction blocked = new Transaction("tx-c", "acct-2", "dev-2", "payee-r",
                600_000, "CNY", System.currentTimeMillis(),
                Map.of("deviceFirstSeenDays", 1.0));
        first.engine.submit(blocked);
        first.reviews.fileAppeal("tx-c", "mistake");
        first.reviews.resolveAppeal(new Operator("rev-1", Set.of(Role.REVIEWER)),
                "tx-c", true, "verified");
        long usageBefore = first.limiter.currentAmount("account:acct-1",
                System.currentTimeMillis(), first.props.getAccountWindowMillis());
        assertEquals(60_000, usageBefore);

        // --- simulated restart: brand-new components, same journal dir ---
        World second = new World(journalDir);
        assertTrue(second.idempotency.find("tx-a").isEmpty());
        second.recover();

        // Completed decisions survive; resubmission folds without re-execution.
        SubmissionResult afterRestart = second.engine.submit(tx("tx-a", "acct-1", 30_000));
        assertTrue(afterRestart.duplicate());
        assertEquals(DecisionType.ALLOW, afterRestart.record().decision());

        // Limit counters are consistent (no double count, no loss).
        long usageAfter = second.limiter.currentAmount("account:acct-1",
                System.currentTimeMillis(), second.props.getAccountWindowMillis());
        assertEquals(60_000, usageAfter);

        // Appeal outcome survives the restart.
        assertEquals(DecisionType.ALLOW,
                second.reviews.effectiveDisposition("tx-c").orElseThrow());

        // Audit trail survives.
        assertFalse(second.audit.byTransaction("tx-c").isEmpty());

        // A transaction never completed before the crash is distinguishable:
        // it is simply absent and can be decided fresh.
        assertTrue(second.idempotency.find("tx-never").isEmpty());
        SubmissionResult fresh = second.engine.submit(tx("tx-never", "acct-1", 10_000));
        assertFalse(fresh.duplicate());
        assertEquals(DecisionType.ALLOW, fresh.record().decision());
    }
}
