package com.github.shikejing.transactionfraudcontrolsjava.fraud.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Disposition;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 处置记录存储：复核/申诉只追加不改写，重启重放恢复。
 */
public class JournalBackedDispositionStore {

    static final String EVENT_TYPE = "disposition";

    private final Journal journal;
    private final ObjectMapper mapper;
    private final Map<String, List<Disposition>> byTransaction = new LinkedHashMap<>();

    public JournalBackedDispositionStore(Journal journal, ObjectMapper mapper) {
        this.journal = journal;
        this.mapper = mapper;
    }

    public void recover() {
        for (Journal.Entry entry : journal.replay()) {
            if (!EVENT_TYPE.equals(entry.type())) {
                continue;
            }
            try {
                apply(mapper.readValue(entry.payloadJson(), Disposition.class));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void apply(Disposition disposition) {
        byTransaction.computeIfAbsent(disposition.transactionId(), k -> new ArrayList<>())
                .add(disposition);
    }

    public synchronized Disposition append(Disposition disposition) {
        try {
            journal.append(EVENT_TYPE, mapper.writeValueAsString(disposition));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        apply(disposition);
        return disposition;
    }

    public synchronized List<Disposition> of(String transactionId) {
        return List.copyOf(byTransaction.getOrDefault(transactionId, List.of()));
    }

    /** 某交易当前生效的处置（取最后一条）。 */
    public synchronized Optional<Disposition> latestOf(String transactionId) {
        List<Disposition> list = byTransaction.get(transactionId);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(list.size() - 1));
    }
}
