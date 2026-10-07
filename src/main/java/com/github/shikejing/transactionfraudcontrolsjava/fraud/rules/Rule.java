package com.github.shikejing.transactionfraudcontrolsjava.fraud.rules;

/**
 * 一条可配置评分规则：观测信号超过阈值即按权重贡献分数。
 * missingSignalScore 用于信号缺失时的独立计分（与观测为 0 区分）。
 */
public record Rule(
        String ruleId,
        String signal,
        double threshold,
        double weight,
        double missingSignalScore,
        String description) {
}
