package com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Deduplicates transaction submissions by transactionId. A repeated
 * submission (retry) is folded into the original decision: it never
 * re-accumulates limits and never re-triggers block actions. Concurrent
 * first-time submissions of the same id race on a single future, so
 * exactly one thread performs the decision.
 */
@Component
public class IdempotencyStore {

    public enum BeginResult {
        /** First time we see this transaction; caller must decide and complete. */
        NEW,
        /** A decision is currently being computed by another thread; wait/attach. */
        IN_PROGRESS,
        /** A completed decision already exists; return it as-is. */
        COMPLETED
    }

    private final ConcurrentHashMap<String, CompletableFuture<DecisionRecord>> decisions =
            new ConcurrentHashMap<>();

    /** Attempt to begin deciding this transaction. */
    public BeginResult begin(String transactionId) {
        CompletableFuture<DecisionRecord> fresh = new CompletableFuture<>();
        CompletableFuture<DecisionRecord> existing = decisions.putIfAbsent(transactionId, fresh);
        if (existing == null) {
            return BeginResult.NEW;
        }
        return existing.isDone() ? BeginResult.COMPLETED : BeginResult.IN_PROGRESS;
    }

    /** Wait for an in-progress decision and return it. */
    public Optional<DecisionRecord> awaitCompletion(String transactionId, long timeoutMillis) {
        CompletableFuture<DecisionRecord> future = decisions.get(transactionId);
        if (future == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(future.get(timeoutMillis, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Store the completed decision and wake any waiters. */
    public void complete(DecisionRecord record) {
        CompletableFuture<DecisionRecord> future =
                decisions.computeIfAbsent(record.transactionId(), k -> new CompletableFuture<>());
        future.complete(record);
    }

    /** Look up an existing completed decision. */
    public Optional<DecisionRecord> find(String transactionId) {
        CompletableFuture<DecisionRecord> future = decisions.get(transactionId);
        if (future == null || !future.isDone()) {
            return Optional.empty();
        }
        return Optional.ofNullable(future.getNow(null));
    }

    /** Restore a completed decision during recovery (no waiters to wake). */
    public void restore(DecisionRecord record) {
        decisions.put(record.transactionId(), CompletableFuture.completedFuture(record));
    }
}
