package com.github.shikejing.transactionfraudcontrolsjava.fraud.engine;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditEvent;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditEventType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.config.FraudProperties;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.LimitPolicy;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.VelocityLimiter;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListKeyType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListMatch;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence.JournalStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSetVersion;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.RiskScorer;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.ScoreResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates one transaction through: idempotency -> block/watch list
 * check -> scoring under the rule version active at acceptance ->
 * velocity/amount limits -> decision + journal + audit. All in-process.
 *
 * <p>Duplicates (same transactionId) fold into the original decision and
 * never re-accumulate limits or re-trigger block actions. Limit
 * acquisition is atomic per key, so concurrent transactions on one
 * account/device converge without overshoot; different keys use
 * independent stripes, so a slow key never blocks unrelated traffic.
 */
@Service
public class FraudDecisionEngine {

    private final IdempotencyStore idempotencyStore;
    private final RuleRegistry ruleRegistry;
    private final ListRegistry listRegistry;
    private final RiskScorer scorer;
    private final VelocityLimiter limiter;
    private final AuditLog auditLog;
    private final JournalStore journalStore;
    private final FraudProperties properties;

    public FraudDecisionEngine(IdempotencyStore idempotencyStore,
                               RuleRegistry ruleRegistry,
                               ListRegistry listRegistry,
                               RiskScorer scorer,
                               VelocityLimiter limiter,
                               AuditLog auditLog,
                               JournalStore journalStore,
                               FraudProperties properties) {
        this.idempotencyStore = idempotencyStore;
        this.ruleRegistry = ruleRegistry;
        this.listRegistry = listRegistry;
        this.scorer = scorer;
        this.limiter = limiter;
        this.auditLog = auditLog;
        this.journalStore = journalStore;
        this.properties = properties;
    }

    /** Accept and decide a transaction; duplicates fold into the original. */
    public SubmissionResult submit(Transaction transaction) {
        IdempotencyStore.BeginResult begin = idempotencyStore.begin(transaction.transactionId());
        switch (begin) {
            case COMPLETED -> {
                return new SubmissionResult(
                        idempotencyStore.find(transaction.transactionId()).orElseThrow(), true);
            }
            case IN_PROGRESS -> {
                Optional<DecisionRecord> decided = idempotencyStore.awaitCompletion(
                        transaction.transactionId(), properties.getDuplicateWaitMillis());
                return decided.map(r -> new SubmissionResult(r, true))
                        .orElseGet(() -> new SubmissionResult(decide(transaction), false));
            }
            case NEW -> {
                return new SubmissionResult(decide(transaction), false);
            }
            default -> throw new IllegalStateException("unexpected: " + begin);
        }
    }

    private DecisionRecord decide(Transaction tx) {
        long now = System.currentTimeMillis();
        // Bind the rule version active at acceptance time.
        RuleSetVersion rules = ruleRegistry.current();

        List<String> listHits = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        // 1. Disposition lists (single atomic snapshot per lookup).
        Optional<ListMatch> blockHit = firstMatch(tx, now, com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListKind.BLOCK);
        Optional<ListMatch> watchHit = firstMatch(tx, now, com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListKind.WATCH);
        blockHit.ifPresent(m -> listHits.add(describe("BLOCK", m)));
        watchHit.ifPresent(m -> listHits.add(describe("WATCH", m)));

        // 2. Deterministic scoring under the bound version. The transaction
        // amount is always exposed as the built-in "amountCents" signal.
        ScoreResult score = scorer.score(enrich(tx), rules);

        // 3. Decide.
        DecisionType decision;
        if (blockHit.isPresent()) {
            decision = DecisionType.BLOCK;
            reasons.add("matched block list: " + describe("BLOCK", blockHit.get()));
        } else if (score.score() >= rules.blockThreshold()) {
            decision = DecisionType.BLOCK;
            reasons.add("score " + score.score() + " >= block threshold "
                    + rules.blockThreshold() + " of rule version " + rules.version());
        } else if (score.score() >= rules.reviewThreshold()) {
            decision = DecisionType.REVIEW;
            reasons.add("score " + score.score() + " >= review threshold "
                    + rules.reviewThreshold() + " of rule version " + rules.version());
        } else if (watchHit.isPresent()) {
            decision = DecisionType.REVIEW;
            reasons.add("matched watch list: " + describe("WATCH", watchHit.get()));
        } else {
            decision = DecisionType.ALLOW;
            reasons.add("score " + score.score() + " below review threshold "
                    + rules.reviewThreshold() + " of rule version " + rules.version());
        }

        // 4. Velocity/amount limits gate ALLOW only; acquisition is atomic.
        if (decision == DecisionType.ALLOW) {
            LimitPolicy accountPolicy = new LimitPolicy(
                    properties.getAccountMaxAmountCentsPerWindow(),
                    properties.getAccountMaxCountPerWindow(),
                    properties.getAccountWindowMillis());
            LimitPolicy devicePolicy = new LimitPolicy(
                    properties.getDeviceMaxAmountCentsPerWindow(),
                    properties.getDeviceMaxCountPerWindow(),
                    properties.getDeviceWindowMillis());
            boolean accountOk = limiter.tryAcquire(
                    accountKey(tx.accountId()), tx.amountCents(), now, accountPolicy);
            boolean deviceOk = accountOk && limiter.tryAcquire(
                    deviceKey(tx.deviceId()), tx.amountCents(), now, devicePolicy);
            if (!accountOk || !deviceOk) {
                decision = DecisionType.REVIEW;
                reasons.add("velocity/amount limit exceeded for "
                        + (!accountOk ? "account " + tx.accountId() : "device " + tx.deviceId()));
            } else {
                journalStore.journalLimitUsage(accountKey(tx.accountId()), tx.amountCents(), now);
                journalStore.journalLimitUsage(deviceKey(tx.deviceId()), tx.amountCents(), now);
            }
        }

        DecisionRecord record = new DecisionRecord(
                tx.transactionId(), tx.accountId(), tx.deviceId(), tx.payeeId(),
                tx.amountCents(), decision, score.score(), rules.version(),
                score.hits(), listHits, reasons, now);

        // Journal before publishing the result: a crash in between leaves an
        // uncommitted (distinguishable) decision that can be re-decided.
        journalStore.journalDecision(record);
        idempotencyStore.complete(record);

        AuditEvent event = new AuditEvent(AuditEventType.DECISION, "system",
                tx.transactionId(), tx.accountId(),
                "decision=" + decision + " score=" + score.score()
                        + " ruleVersion=" + rules.version() + " reasons=" + reasons,
                now);
        auditLog.append(event);
        journalStore.journalAudit(event);
        return record;
    }

    private static Transaction enrich(Transaction tx) {
        java.util.Map<String, Double> signals = new java.util.HashMap<>(tx.signals());
        signals.putIfAbsent("amountCents", (double) tx.amountCents());
        return new Transaction(tx.transactionId(), tx.accountId(), tx.deviceId(), tx.payeeId(),
                tx.amountCents(), tx.currency(), tx.occurredAtEpochMs(), signals);
    }

    private Optional<ListMatch> firstMatch(Transaction tx, long now,
                                           com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListKind kind) {
        return java.util.stream.Stream.of(
                        listRegistry.lookup(ListKeyType.ACCOUNT, tx.accountId(), now),
                        listRegistry.lookup(ListKeyType.DEVICE, tx.deviceId(), now),
                        listRegistry.lookup(ListKeyType.PAYEE, tx.payeeId(), now))
                .flatMap(Optional::stream)
                .filter(m -> m.entry().kind() == kind)
                .findFirst();
    }

    private static String describe(String label, ListMatch match) {
        return label + " list hit on " + match.keyType() + "=" + match.keyValue()
                + " (source=" + match.entry().source()
                + ", priority=" + match.entry().priority() + ")";
    }

    static String accountKey(String accountId) {
        return "account:" + accountId;
    }

    static String deviceKey(String deviceId) {
        return "device:" + deviceId;
    }
}
