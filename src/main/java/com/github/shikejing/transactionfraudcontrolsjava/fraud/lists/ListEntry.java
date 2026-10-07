package com.github.shikejing.transactionfraudcontrolsjava.fraud.lists;

/**
 * One list entry. Higher {@code priority} sources win when several entries
 * match the same key. {@code expiresAtEpochMs} of 0 means never expires.
 */
public record ListEntry(
        ListKeyType keyType,
        String keyValue,
        ListKind kind,
        String source,
        int priority,
        long expiresAtEpochMs) {

    public boolean expiredAt(long nowEpochMs) {
        return expiresAtEpochMs > 0 && nowEpochMs >= expiresAtEpochMs;
    }
}
