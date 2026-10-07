package com.github.shikejing.transactionfraudcontrolsjava.fraud.model;

/**
 * 一条规则的命中明细：可追溯命中规则、当时观测值与得分贡献。
 * observedValue 为 null 表示信号缺失（与观测值为 0 区分）。
 */
public record RuleHit(
        String ruleId,
        String signal,
        Double observedValue,
        boolean signalMissing,
        double threshold,
        double weight,
        double scoreContribution,
        String detail) {
}
