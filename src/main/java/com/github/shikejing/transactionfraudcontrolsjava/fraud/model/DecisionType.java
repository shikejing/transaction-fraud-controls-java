package com.github.shikejing.transactionfraudcontrolsjava.fraud.model;

/**
 * 三类可区分的判定结果。
 */
public enum DecisionType {
    /** 放行 */
    ALLOW,
    /** 进入人工复核 */
    REVIEW,
    /** 直接拦截 */
    BLOCK
}
