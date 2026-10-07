package com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;

/**
 * 纯函数评分器：确定性合成规则与权重。
 */
public interface RiskScorer {

    ScoreResult score(Transaction transaction, RuleSet ruleSet);
}
