package com.github.shikejing.transactionfraudcontrolsjava.fraud.rules;

import java.util.List;

/**
 * An immutable, versioned rule set. Decisions bind to the version that was
 * active at acceptance time; publishing a new version never mutates old ones.
 *
 * @param reviewThreshold score &gt;= this and &lt; blockThreshold leads to REVIEW
 * @param blockThreshold  score &gt;= this leads to BLOCK
 */
public record RuleSetVersion(
        String version,
        List<RiskRule> rules,
        double reviewThreshold,
        double blockThreshold,
        long publishedAtEpochMs) {

    public RuleSetVersion {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
