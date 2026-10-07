package com.github.shikejing.transactionfraudcontrolsjava.fraud.model;

/**
 * 审计事件：每次判定、调整与解除都记录操作人、依据与时间。
 */
public record AuditEvent(
        long sequence,
        String eventType,
        String operator,
        String transactionId,
        String accountId,
        String detail,
        long occurredAtEpochMs) {
}
