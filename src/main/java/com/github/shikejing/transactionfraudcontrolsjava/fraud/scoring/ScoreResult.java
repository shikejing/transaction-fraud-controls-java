package com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.RuleHit;

import java.util.List;

/**
 * 评分结果：总分 + 逐条命中明细，保证相同信号与相同规则版本结果逐项一致。
 */
public record ScoreResult(double totalScore, List<RuleHit> hits) {
}
