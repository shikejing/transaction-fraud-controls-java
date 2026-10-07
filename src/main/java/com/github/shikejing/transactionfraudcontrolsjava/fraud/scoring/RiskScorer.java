package com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.RuleHit;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RiskRule;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSetVersion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure, deterministic scorer: same signals + same rule version always yield
 * the same per-rule hits and total score. Missing signals never fire a rule
 * (and are recorded with signalPresent=false); zero-valued signals are
 * evaluated normally, so missing and zero are distinguishable in the trace.
 */
@Component
public class RiskScorer {

    public ScoreResult score(Transaction transaction, RuleSetVersion ruleSet) {
        List<RuleHit> hits = new ArrayList<>();
        double total = 0.0;
        for (RiskRule rule : ruleSet.rules()) {
            Double observed = transaction.signals().get(rule.signalName());
            boolean present = observed != null;
            boolean matched = present && observed >= rule.threshold();
            double contribution = matched ? rule.weight() : 0.0;
            total += contribution;
            hits.add(new RuleHit(
                    rule.ruleId(),
                    ruleSet.version(),
                    rule.signalName(),
                    present,
                    observed,
                    rule.threshold(),
                    rule.weight(),
                    matched,
                    contribution));
        }
        return new ScoreResult(total, ruleSet.version(), hits);
    }
}
