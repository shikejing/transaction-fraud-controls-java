package com.github.shikejing.transactionfraudcontrolsjava.fraud.rules;

import java.util.List;

/**
 * 一个不可变的规则版本：交易受理时绑定当时生效的版本，
 * 之后规则调整不回溯影响已受理交易。
 */
public record RuleSet(
        String version,
        List<Rule> rules,
        double reviewThreshold,
        double blockThreshold,
        long accountDailyLimitCents,
        int deviceMaxTxPerWindow,
        long deviceWindowMs,
        long createdAtEpochMs) {
}
