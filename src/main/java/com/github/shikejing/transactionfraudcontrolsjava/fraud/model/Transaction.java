package com.github.shikejing.transactionfraudcontrolsjava.fraud.model;

import java.util.Map;

/**
 * 一笔待判定的交易。signals 中“键不存在”表示信号缺失，
 * 与“键存在但值为 0”语义不同，评分时必须区别对待。
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

    public boolean hasSignal(String name) {
        return signals != null && signals.containsKey(name);
    }

    public Double signal(String name) {
        return signals == null ? null : signals.get(name);
    }
}
