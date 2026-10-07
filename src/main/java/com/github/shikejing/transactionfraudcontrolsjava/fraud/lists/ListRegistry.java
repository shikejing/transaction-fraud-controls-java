package com.github.shikejing.transactionfraudcontrolsjava.fraud.lists;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Disposition list store. Updates copy-on-write an immutable snapshot and
 * swap it atomically, so any single lookup always sees one consistent
 * generation of the list — never half-old/half-new. When several entries
 * match the same key, the highest-priority source wins.
 */
@Component
public class ListRegistry {

    private final AtomicReference<List<ListEntry>> snapshot = new AtomicReference<>(List.of());

    /** Highest-priority non-expired entry matching the key, if any. */
    public Optional<ListMatch> lookup(ListKeyType keyType, String keyValue, long nowEpochMs) {
        List<ListEntry> view = snapshot.get();
        return view.stream()
                .filter(e -> e.keyType() == keyType && e.keyValue().equals(keyValue))
                .filter(e -> !e.expiredAt(nowEpochMs))
                .max(Comparator.comparingInt(ListEntry::priority))
                .map(e -> new ListMatch(keyType, keyValue, e));
    }

    /** Atomically add or replace an entry (same keyType+keyValue+kind+source). */
    public void upsert(ListEntry entry) {
        snapshot.updateAndGet(view -> {
            List<ListEntry> next = new ArrayList<>(view);
            next.removeIf(e -> e.keyType() == entry.keyType()
                    && e.keyValue().equals(entry.keyValue())
                    && e.kind() == entry.kind()
                    && e.source().equals(entry.source()));
            next.add(entry);
            return List.copyOf(next);
        });
    }

    /** Atomically remove an entry. */
    public void remove(ListKeyType keyType, String keyValue, ListKind kind, String source) {
        snapshot.updateAndGet(view -> view.stream()
                .filter(e -> !(e.keyType() == keyType
                        && e.keyValue().equals(keyValue)
                        && e.kind() == kind
                        && e.source().equals(source)))
                .toList());
    }

    /** Current snapshot, for audit and tests. */
    public List<ListEntry> snapshot() {
        return snapshot.get();
    }
}
