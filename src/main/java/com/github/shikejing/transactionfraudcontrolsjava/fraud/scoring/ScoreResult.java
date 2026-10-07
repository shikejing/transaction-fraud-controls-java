package com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.RuleHit;

import java.util.List;

/**
 * Deterministic scoring output for one transaction under one rule version.
 */
public record ScoreResult(double score, String ruleVersion, List<RuleHit> hits) {

    public ScoreResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
