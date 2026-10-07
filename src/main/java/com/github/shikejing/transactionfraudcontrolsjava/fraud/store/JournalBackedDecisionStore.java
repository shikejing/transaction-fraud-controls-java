package com.github.shikejing.transactionfraudcontrolsjava.fraud.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionStatus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 判定记录存储：写入先落日志再进内存索引，重启重放日志恢复。
 * 同一 transactionId 只接受首次写入，重复提交返回已有记录（幂等归并）。
 */
public class JournalBackedDecisionStore implements DecisionStore {

    static final String EVENT_TYPE = "decision";

    private final Journal journal;
    private final ObjectMapper mapper;
    private final Map<String, DecisionRecord> byId = new LinkedHashMap<>();
    private final Map<String, List<String>> accountIndex = new LinkedHashMap<>();

    public JournalBackedDecisionStore(Journal journal, ObjectMapper mapper) {
        this.journal = journal;
        this.mapper = mapper;
    }

    /** 重启恢复：重放日志重建索引。 */
    public void recover() {
        for (Journal.Entry entry : journal.replay()) {
            if (!EVENT_TYPE.equals(entry.type())) {
                continue;
            }
            try {
                apply(mapper.readValue(entry.payloadJson(), DecisionRecord.class));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void apply(DecisionRecord record) {
        DecisionRecord previous = byId.put(record.transactionId(), record);
        if (previous == null) {
            accountIndex.computeIfAbsent(record.accountId(), k -> new ArrayList<>())
                    .add(record.transactionId());
        }
    }

    @Override
    public synchronized DecisionRecord saveIfAbsent(DecisionRecord record) {
        DecisionRecord existing = byId.get(record.transactionId());
        if (existing != null) {
            return existing;
        }
        try {
            journal.append(EVENT_TYPE, mapper.writeValueAsString(record));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        apply(record);
        return record;
    }

    /** 判定完成后用 COMPLETED 版本覆盖 PENDING（同一事务的推进，不是重复提交）。 */
    public synchronized DecisionRecord complete(DecisionRecord completed) {
        try {
            journal.append(EVENT_TYPE, mapper.writeValueAsString(completed));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        apply(completed);
        return completed;
    }

    @Override
    public synchronized Optional<DecisionRecord> byTransactionId(String transactionId) {
        return Optional.ofNullable(byId.get(transactionId));
    }

    @Override
    public synchronized List<DecisionRecord> byAccountId(String accountId) {
        List<String> ids = accountIndex.getOrDefault(accountId, List.of());
        List<DecisionRecord> result = new ArrayList<>(ids.size());
        for (String id : ids) {
            result.add(byId.get(id));
        }
        return result;
    }

    @Override
    public synchronized List<DecisionRecord> pendingRecords() {
        List<DecisionRecord> pending = new ArrayList<>();
        for (DecisionRecord record : byId.values()) {
            if (record.status() == DecisionStatus.PENDING) {
                pending.add(record);
            }
        }
        return pending;
    }
}
