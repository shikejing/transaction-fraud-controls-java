package com.github.shikejing.transactionfraudcontrolsjava.fraud.rules;

/**
 * One weighted rule: fires when signal {@code signalName} is observed
 * with value &gt;= {@code threshold}. A missing signal never fires.
 */
public record RiskRule(
        String ruleId,
        String signalName,
        double threshold,
        double weight,
        String description) {
}
