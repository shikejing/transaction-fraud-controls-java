package com.github.shikejing.transactionfraudcontrolsjava.fraud.audit;

/**
 * Kinds of auditable events.
 */
public enum AuditEventType {
    DECISION,
    RULE_PUBLISHED,
    LIST_CHANGED,
    REVIEW_DECIDED,
    APPEAL_FILED,
    APPEAL_RESOLVED,
    BLOCK_RELEASED,
    ACCESS_DENIED
}
