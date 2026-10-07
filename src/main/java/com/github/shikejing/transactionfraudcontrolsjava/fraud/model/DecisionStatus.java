package com.github.shikejing.transactionfraudcontrolsjava.fraud.model;

/**
 * 判定记录的生命周期：受理后先落 PENDING，判定完成才到 COMPLETED，
 * 进程中断恢复时未完成的记录可与已提交结果区分。
 */
public enum DecisionStatus {
    PENDING,
    COMPLETED
}
