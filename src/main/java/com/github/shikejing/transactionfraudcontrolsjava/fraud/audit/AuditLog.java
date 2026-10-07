package com.github.shikejing.transactionfraudcontrolsjava.fraud.audit;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Append-only audit trail, queryable by transaction or account.
 */
@Component
public class AuditLog {

    private final CopyOnWriteArrayList<AuditEvent> events = new CopyOnWriteArrayList<>();

    public void append(AuditEvent event) {
        events.add(event);
    }

    public List<AuditEvent> byTransaction(String transactionId) {
        return events.stream()
                .filter(e -> transactionId.equals(e.transactionId()))
                .toList();
    }

    public List<AuditEvent> byAccount(String accountId) {
        return events.stream()
                .filter(e -> accountId.equals(e.accountId()))
                .toList();
    }

    public List<AuditEvent> all() {
        return List.copyOf(events);
    }
}
