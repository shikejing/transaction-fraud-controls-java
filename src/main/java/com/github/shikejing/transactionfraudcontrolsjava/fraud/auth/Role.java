package com.github.shikejing.transactionfraudcontrolsjava.fraud.auth;

/**
 * 角色：决定可执行的操作范围。
 */
public enum Role {
    /** 提交交易 */
    SUBMITTER,
    /** 复核判定、处理申诉 */
    REVIEWER,
    /** 调整规则与名单 */
    RULE_ADMIN,
    /** 解除拦截 */
    DISPOSITION_ADMIN
}
