package com.github.shikejing.transactionfraudcontrolsjava.fraud.model;

import java.util.List;

/**
 * 一次判定的不可变结果记录。复核/申诉不改写本记录，只追加新的处置记录。
 */
public record DecisionRecord(
        String transactionId,
        String accountId,
        String deviceId,
        String payeeId,
        long amountCents,
        DecisionStatus status,
        DecisionType decision,
        double totalScore,
        String ruleVersion,
        List<RuleHit> hits,
        List<String> reasons,
        long acceptedAtEpochMs,
        long decidedAtEpochMs) {
}
