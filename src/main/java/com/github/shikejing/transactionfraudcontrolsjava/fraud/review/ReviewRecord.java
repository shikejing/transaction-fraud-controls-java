package com.github.shikejing.transactionfraudcontrolsjava.fraud.review;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionType;

/**
 * A review or appeal outcome. Appended separately; the original
 * DecisionRecord is never rewritten.
 */
public record ReviewRecord(
        String reviewId,
        String transactionId,
        String operatorId,
        Kind kind,
        DecisionType outcome,
        String rationale,
        long atEpochMs) {

    public enum Kind {
        /** Manual review of a REVIEW decision. */
        REVIEW,
        /** Appeal against a BLOCK decision. */
        APPEAL
    }
}
