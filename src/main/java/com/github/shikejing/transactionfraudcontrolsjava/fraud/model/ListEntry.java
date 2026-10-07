package com.github.shikejing.transactionfraudcontrolsjava.fraud.model;

/**
 * 名单条目：带来源（决定优先级）与过期时间。
 */
public record ListEntry(
        String key,
        ListKind kind,
        String source,
        int priority,
        long expiresAtEpochMs) {

    public boolean expiredAt(long nowEpochMs) {
        return expiresAtEpochMs > 0 && nowEpochMs >= expiresAtEpochMs;
    }
}
