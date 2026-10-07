package com.github.shikejing.transactionfraudcontrolsjava.fraud.audit;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.AuditEvent;

import java.util.List;

/**
 * 审计服务：判定、调整与解除全部留痕，可按交易或账号追溯。
 */
public interface AuditService {

    void record(String eventType, String operator, String transactionId, String accountId, String detail);

    List<AuditEvent> byTransaction(String transactionId);

    List<AuditEvent> byAccount(String accountId);
}
