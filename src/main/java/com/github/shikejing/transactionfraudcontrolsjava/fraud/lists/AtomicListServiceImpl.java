package com.github.shikejing.transactionfraudcontrolsjava.fraud.lists;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListEntry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.Journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 名单服务：整份名单是一个不可变快照，变更构建新快照后原子替换。
 * 任一时刻对同一交易读到的名单状态来自同一个快照，不会半新半旧。
 * 变更先写日志再切换快照，重启重放恢复。
 */
public class AtomicListServiceImpl implements ListService {

    static final String EVENT_TYPE = "list-change";

    private final Journal journal;
    private final ObjectMapper mapper;
    private final AtomicReference<ImmutableSnapshot> current =
            new AtomicReference<>(new ImmutableSnapshot(Map.of()));

    public AtomicListServiceImpl(Journal journal, ObjectMapper mapper) {
        this.journal = journal;
        this.mapper = mapper;
    }

    public void recover() {
        for (Journal.Entry entry : journal.replay()) {
            if (!EVENT_TYPE.equals(entry.type())) {
                continue;
            }
            try {
                ChangeSet change = mapper.readValue(entry.payloadJson(), ChangeSet.class);
                current.set(applyChange(current.get(), change.additions(), change.removals()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public ListSnapshot snapshot() {
        return current.get();
    }

    @Override
    public ListSnapshot apply(Collection<ListEntry> additions, Collection<String> removals) {
        List<ListEntry> adds = additions == null ? List.of() : List.copyOf(additions);
        List<String> rems = removals == null ? List.of() : List.copyOf(removals);
        synchronized (this) {
            try {
                journal.append(EVENT_TYPE, mapper.writeValueAsString(new ChangeSet(adds, rems)));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ImmutableSnapshot next = applyChange(current.get(), adds, rems);
            current.set(next);
            return next;
        }
    }

    private static ImmutableSnapshot applyChange(ImmutableSnapshot base,
                                                 List<ListEntry> additions,
                                                 List<String> removals) {
        Map<String, ListEntry> next = new LinkedHashMap<>(base.entries);
        for (String key : removals) {
            next.remove(key);
        }
        for (ListEntry entry : additions) {
            ListEntry existing = next.get(entry.key());
            // 同键多条目时保留来源优先级更高者；优先级相同后写覆盖
            if (existing == null || entry.priority() >= existing.priority()) {
                next.put(entry.key(), entry);
            }
        }
        return new ImmutableSnapshot(Map.copyOf(next));
    }

    record ChangeSet(List<ListEntry> additions, List<String> removals) {
    }

    static final class ImmutableSnapshot implements ListSnapshot {

        private final Map<String, ListEntry> entries;

        ImmutableSnapshot(Map<String, ListEntry> entries) {
            this.entries = entries;
        }

        @Override
        public Optional<ListEntry> lookup(String key, long nowEpochMs) {
            ListEntry entry = entries.get(key);
            if (entry == null || entry.expiredAt(nowEpochMs)) {
                return Optional.empty();
            }
            return Optional.of(entry);
        }

        @Override
        public Map<String, ListEntry> entries() {
            return entries;
        }
    }
}
