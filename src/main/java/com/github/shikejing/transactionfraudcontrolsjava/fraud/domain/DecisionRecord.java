package com.github.shikejing.transactionfraudcontrolsjava.fraud.domain;

import java.util.List;

/**
 * Immutable record of a completed decision. Once written it is never
 * rewritten; reviews and appeals append separate records.
 */
public record DecisionRecord(
        String transactionId,
        String accountId,
        String deviceId,
        String payeeId,
        long amountCents,
        DecisionType decision,
        double score,
        String ruleVersion,
        List<RuleHit> ruleHits,
        List<String> listHits,
        List<String> reasons,
        long decidedAtEpochMs) {

    public DecisionRecord {
        ruleHits = ruleHits == null ? List.of() : List.copyOf(ruleHits);
        listHits = listHits == null ? List.of() : List.copyOf(listHits);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
