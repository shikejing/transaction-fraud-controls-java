package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditEvent;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditEventType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AccessDeniedException;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AuthorizationService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.config.FraudProperties;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Operator;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.FraudDecisionEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.VelocityLimiter;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence.JournalStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.review.ReviewRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.review.ReviewService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.RiskScorer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewAndAuthTest {

    private IdempotencyStore idempotencyStore;
    private AuditLog auditLog;
    private ReviewService reviewService;
    private FraudDecisionEngine engine;
    private AuthorizationService authorizationService;

    private static final Operator REVIEWER = new Operator("rev-1", Set.of(Role.REVIEWER));
    private static final Operator PLAIN = new Operator("user-1", Set.of());

    @BeforeEach
    void setUp() {
        FraudProperties props = new FraudProperties();
        idempotencyStore = new IdempotencyStore();
        auditLog = new AuditLog();
        authorizationService = new AuthorizationService();
        JournalStore journal = new JournalStore(props);
        reviewService = new ReviewService(authorizationService, idempotencyStore, auditLog, journal);
        engine = new FraudDecisionEngine(idempotencyStore, new RuleRegistry(), new ListRegistry(),
                new RiskScorer(), new VelocityLimiter(), auditLog, journal, props);
    }

    private Transaction blockedTx(String id) {
        // amountCents 600_000 >= 500_000 (weight 40) + deviceFirstSeenDays 1.0
        // fires R-NEW-DEVICE (weight 25) = 65 >= 60 -> BLOCK
        return new Transaction(id, "acct-r", "dev-r", "payee-r", 600_000, "CNY",
                System.currentTimeMillis(), Map.of("deviceFirstSeenDays", 1.0));
    }

    @Test
    void falselyBlockedTransactionHasExplainableReleasePath() {
        DecisionRecord original = engine.submit(blockedTx("tx-fp")).record();
        assertEquals(DecisionType.BLOCK, original.decision());

        reviewService.fileAppeal("tx-fp", "customer provided invoice");
        ReviewRecord resolution = reviewService.resolveAppeal(
                REVIEWER, "tx-fp", true, "verified legitimate purchase");

        assertEquals(DecisionType.ALLOW, resolution.outcome());
        assertEquals(DecisionType.ALLOW,
                reviewService.effectiveDisposition("tx-fp").orElseThrow());

        // Original decision record is NOT rewritten.
        DecisionRecord stored = idempotencyStore.find("tx-fp").orElseThrow();
        assertEquals(DecisionType.BLOCK, stored.decision());
        assertEquals(original, stored);

        // Full trail is auditable by transaction.
        List<AuditEvent> trail = auditLog.byTransaction("tx-fp");
        assertTrue(trail.stream().anyMatch(e -> e.type() == AuditEventType.DECISION));
        assertTrue(trail.stream().anyMatch(e -> e.type() == AuditEventType.APPEAL_FILED));
        assertTrue(trail.stream().anyMatch(e -> e.type() == AuditEventType.BLOCK_RELEASED));
        assertTrue(trail.stream()
                .filter(e -> e.type() == AuditEventType.BLOCK_RELEASED)
                .allMatch(e -> e.operatorId().equals("rev-1")));
    }

    @Test
    void onlyReviewerRoleCanResolveAppeals() {
        engine.submit(blockedTx("tx-auth")).record();
        reviewService.fileAppeal("tx-auth", "please recheck");

        AccessDeniedException denied = assertThrows(AccessDeniedException.class,
                () -> reviewService.resolveAppeal(PLAIN, "tx-auth", true, "trying"));
        assertEquals(Role.REVIEWER, denied.requiredRole());
        assertEquals("user-1", denied.subjectId());

        // Admin implies all roles.
        Operator admin = new Operator("admin-1", Set.of(Role.ADMIN));
        ReviewRecord ok = reviewService.resolveAppeal(admin, "tx-auth", true, "ok");
        assertEquals(DecisionType.ALLOW, ok.outcome());
    }

    @Test
    void reviewOnlyAppliesToHeldTransactions() {
        DecisionRecord held = engine.submit(new Transaction("tx-hold", "acct-h", "dev-h",
                "payee-h", 100, "CNY", System.currentTimeMillis(),
                Map.of("accountTxCount1h", 12.0))).record(); // score 20 -> REVIEW? v1 review=30
        // 12 >= 10 -> weight 20 < 30 -> ALLOW actually; force REVIEW via watch list instead.
        assertEquals(DecisionType.ALLOW, held.decision());
        assertThrows(IllegalStateException.class,
                () -> reviewService.decideReview(REVIEWER, "tx-hold", DecisionType.ALLOW, "x"));
    }

    @Test
    void authorizationServiceRejectsMissingRolesWithDistinctReason() {
        AccessDeniedException e = assertThrows(AccessDeniedException.class,
                () -> authorizationService.require(PLAIN, Role.RULE_MANAGER));
        assertEquals(Role.RULE_MANAGER, e.requiredRole());
        assertTrue(e.getMessage().contains("RULE_MANAGER"));
    }
}
