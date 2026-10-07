package com.github.shikejing.transactionfraudcontrolsjava.fraud.domain;

import java.util.Map;
import java.util.Objects;

/**
 * A single incoming transaction. Signals are local observations keyed by name;
 * an absent key means the signal is MISSING, a present key with value 0.0
 * means the signal was observed as zero. The two are treated differently
 * by the scorer.
 */
public record Transaction(
        String transactionId,
        String accountId,
        String deviceId,
        String payeeId,
        long amountCents,
        String currency,
        long occurredAtEpochMs,
        Map<String, Double> signals) {

    public Transaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(payeeId, "payeeId");
        signals = signals == null ? Map.of() : Map.copyOf(signals);
    }
}
