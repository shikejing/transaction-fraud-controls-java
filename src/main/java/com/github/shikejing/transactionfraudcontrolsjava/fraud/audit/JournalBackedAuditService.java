package com.github.shikejing.transactionfraudcontrolsjava.fraud.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.AuditEvent;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.Journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 审计服务：事件先落日志再入内存索引，重启重放恢复，序列号单调递增。
 */
public class JournalBackedAuditService implements AuditService {

    static final String EVENT_TYPE = "audit";

    private final Journal journal;
    private final ObjectMapper mapper;
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public JournalBackedAuditService(Journal journal, ObjectMapper mapper) {
        this.journal = journal;
        this.mapper = mapper;
    }

    public void recover() {
        long maxSeq = 0;
        for (Journal.Entry entry : journal.replay()) {
            if (!EVENT_TYPE.equals(entry.type())) {
                continue;
            }
            try {
                AuditEvent event = mapper.readValue(entry.payloadJson(), AuditEvent.class);
                events.add(event);
                maxSeq = Math.max(maxSeq, event.sequence());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        sequence.set(maxSeq);
    }

    @Override
    public void record(String eventType, String operator, String transactionId,
                       String accountId, String detail) {
        AuditEvent event = new AuditEvent(sequence.incrementAndGet(), eventType, operator,
                transactionId, accountId, detail, System.currentTimeMillis());
        try {
            journal.append(EVENT_TYPE, mapper.writeValueAsString(event));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        events.add(event);
    }

    @Override
    public List<AuditEvent> byTransaction(String transactionId) {
        List<AuditEvent> result = new ArrayList<>();
        for (AuditEvent event : events) {
            if (transactionId.equals(event.transactionId())) {
                result.add(event);
            }
        }
        return result;
    }

    @Override
    public List<AuditEvent> byAccount(String accountId) {
        List<AuditEvent> result = new ArrayList<>();
        for (AuditEvent event : events) {
            if (accountId.equals(event.accountId())) {
                result.add(event);
            }
        }
        return result;
    }
}
