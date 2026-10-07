package com.github.shikejing.transactionfraudcontrolsjava.fraud.domain;

/**
 * Outcome of a fraud decision for a single transaction.
 */
public enum DecisionType {
    /** Transaction is allowed to proceed. */
    ALLOW,
    /** Transaction is held for manual review. */
    REVIEW,
    /** Transaction is blocked. */
    BLOCK
}
