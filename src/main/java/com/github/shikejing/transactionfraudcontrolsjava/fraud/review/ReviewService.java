package com.github.shikejing.transactionfraudcontrolsjava.fraud.review;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditEvent;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditEventType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AuthorizationService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Operator;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence.JournalStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Review and appeal handling. Only REVIEWER-role operators may decide
 * reviews or release blocks. Outcomes are appended as new records; the
 * original DecisionRecord is never rewritten. All mutations for one
 * transaction are serialized on a per-transaction monitor, so an appeal
 * racing a new decision or a list change resolves to one deterministic
 * order. A falsely blocked transaction has an explainable release path:
 * appeal -> reviewer resolves with release -> effective disposition
 * becomes ALLOW while the original BLOCK record remains for audit.
 */
@Service
public class ReviewService {

    private final AuthorizationService authorizationService;
    private final IdempotencyStore idempotencyStore;
    private final AuditLog auditLog;
    private final JournalStore journalStore;

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<ReviewRecord>> history =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public ReviewService(AuthorizationService authorizationService,
                         IdempotencyStore idempotencyStore,
                         AuditLog auditLog,
                         JournalStore journalStore) {
        this.authorizationService = authorizationService;
        this.idempotencyStore = idempotencyStore;
        this.auditLog = auditLog;
        this.journalStore = journalStore;
    }

    private Object lockFor(String transactionId) {
        return locks.computeIfAbsent(transactionId, k -> new Object());
    }

    /** Decide a held (REVIEW) transaction. */
    public ReviewRecord decideReview(Operator operator, String transactionId,
                                     DecisionType outcome, String rationale) {
        authorizationService.require(operator, Role.REVIEWER);
        synchronized (lockFor(transactionId)) {
            DecisionRecord original = requireOriginal(transactionId);
            if (original.decision() != DecisionType.REVIEW) {
                throw new IllegalStateException(
                        "transaction " + transactionId + " is not pending review (was "
                                + original.decision() + ")");
            }
            ReviewRecord record = append(new ReviewRecord(
                    newReviewId(), transactionId, operator.subjectId(),
                    ReviewRecord.Kind.REVIEW, outcome, rationale,
                    System.currentTimeMillis()));
            audit(operator.subjectId(), transactionId, original.accountId(),
                    AuditEventType.REVIEW_DECIDED,
                    "review outcome=" + outcome + " rationale=" + rationale);
            return record;
        }
    }

    /** File an appeal against a BLOCK decision. */
    public ReviewRecord fileAppeal(String transactionId, String rationale) {
        synchronized (lockFor(transactionId)) {
            DecisionRecord original = requireOriginal(transactionId);
            if (original.decision() != DecisionType.BLOCK) {
                throw new IllegalStateException(
                        "only BLOCK decisions can be appealed (was " + original.decision() + ")");
            }
            ReviewRecord record = append(new ReviewRecord(
                    newReviewId(), transactionId, "appellant",
                    ReviewRecord.Kind.APPEAL, DecisionType.BLOCK, rationale,
                    System.currentTimeMillis()));
            audit("appellant", transactionId, original.accountId(),
                    AuditEventType.APPEAL_FILED, "appeal rationale=" + rationale);
            return record;
        }
    }

    /** Resolve an appeal: release (false positive) or uphold. */
    public ReviewRecord resolveAppeal(Operator operator, String transactionId,
                                      boolean release, String rationale) {
        authorizationService.require(operator, Role.REVIEWER);
        synchronized (lockFor(transactionId)) {
            DecisionRecord original = requireOriginal(transactionId);
            boolean appealed = history.getOrDefault(transactionId, new CopyOnWriteArrayList<>())
                    .stream().anyMatch(r -> r.kind() == ReviewRecord.Kind.APPEAL);
            if (!appealed) {
                throw new IllegalStateException("no appeal filed for " + transactionId);
            }
            DecisionType outcome = release ? DecisionType.ALLOW : DecisionType.BLOCK;
            ReviewRecord record = append(new ReviewRecord(
                    newReviewId(), transactionId, operator.subjectId(),
                    ReviewRecord.Kind.APPEAL, outcome, rationale,
                    System.currentTimeMillis()));
            audit(operator.subjectId(), transactionId, original.accountId(),
                    release ? AuditEventType.BLOCK_RELEASED : AuditEventType.APPEAL_RESOLVED,
                    "appeal resolved release=" + release + " rationale=" + rationale);
            return record;
        }
    }

    /**
     * Effective current disposition of a transaction after reviews/appeals.
     * Empty when nothing changed the original decision.
     */
    public Optional<DecisionType> effectiveDisposition(String transactionId) {
        List<ReviewRecord> records = history.get(transactionId);
        if (records == null || records.isEmpty()) {
            return Optional.empty();
        }
        // Last review/appeal outcome wins; records are appended in order.
        return Optional.of(records.get(records.size() - 1).outcome());
    }

    public List<ReviewRecord> history(String transactionId) {
        return List.copyOf(history.getOrDefault(transactionId, new CopyOnWriteArrayList<>()));
    }

    /** Restore a journaled record during recovery. */
    public void restore(ReviewRecord record) {
        history.computeIfAbsent(record.transactionId(), k -> new CopyOnWriteArrayList<>())
                .add(record);
    }

    private DecisionRecord requireOriginal(String transactionId) {
        return idempotencyStore.find(transactionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown transaction: " + transactionId));
    }

    private ReviewRecord append(ReviewRecord record) {
        history.computeIfAbsent(record.transactionId(), k -> new CopyOnWriteArrayList<>())
                .add(record);
        journalStore.journalReview(record);
        return record;
    }

    private void audit(String operatorId, String transactionId, String accountId,
                       AuditEventType type, String detail) {
        AuditEvent event = new AuditEvent(type, operatorId, transactionId, accountId,
                detail, System.currentTimeMillis());
        auditLog.append(event);
        journalStore.journalAudit(event);
    }

    private static String newReviewId() {
        return "rev-" + UUID.randomUUID();
    }
}
