package com.github.shikejing.transactionfraudcontrolsjava.fraud.domain;

/**
 * Authorization roles. ADMIN implies all others.
 */
public enum Role {
    ADMIN,
    RULE_MANAGER,
    LIST_MANAGER,
    REVIEWER
}
