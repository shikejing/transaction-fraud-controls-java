package com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.RuleHit;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.Rule;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性评分器：只依赖输入交易与规则版本，相同输入必得逐项一致的结果。
 * 信号缺失（键不存在）按 missingSignalScore 计分；信号为零是正常观测值，按阈值比较。
 */
public class DefaultRiskScorer implements RiskScorer {

    @Override
    public ScoreResult score(Transaction transaction, RuleSet ruleSet) {
        List<RuleHit> hits = new ArrayList<>();
        double total = 0d;
        for (Rule rule : ruleSet.rules()) {
            Double observed = transaction.signal(rule.signal());
            double contribution;
            String detail;
            boolean missing = observed == null;
            if (missing) {
                contribution = rule.missingSignalScore();
                detail = "signal missing, missingSignalScore applied";
            } else if (observed >= rule.threshold()) {
                contribution = rule.weight();
                detail = "observed " + observed + " >= threshold " + rule.threshold();
            } else {
                contribution = 0d;
                detail = "observed " + observed + " < threshold " + rule.threshold();
            }
            total += contribution;
            hits.add(new RuleHit(rule.ruleId(), rule.signal(), observed, missing,
                    rule.threshold(), rule.weight(), contribution, detail));
        }
        return new ScoreResult(total, List.copyOf(hits));
    }
}
