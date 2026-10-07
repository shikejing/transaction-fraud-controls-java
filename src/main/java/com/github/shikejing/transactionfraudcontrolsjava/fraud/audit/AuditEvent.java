package com.github.shikejing.transactionfraudcontrolsjava.fraud.audit;

/**
 * One immutable audit entry: who did what, on which subject, why, and when.
 */
public record AuditEvent(
        AuditEventType type,
        String operatorId,
        String transactionId,
        String accountId,
        String detail,
        long atEpochMs) {
}
