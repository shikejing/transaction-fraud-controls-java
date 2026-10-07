package com.github.shikejing.transactionfraudcontrolsjava.fraud.domain;

/**
 * Trace of one rule evaluation against one transaction.
 *
 * @param signalPresent false when the signal was missing (distinguished from zero)
 * @param observedValue the observed signal value, or null when missing
 * @param matched       whether the rule fired
 * @param contribution  score contribution (weight when matched, 0 otherwise)
 */
public record RuleHit(
        String ruleId,
        String ruleVersion,
        String signalName,
        boolean signalPresent,
        Double observedValue,
        double threshold,
        double weight,
        boolean matched,
        double contribution) {
}
