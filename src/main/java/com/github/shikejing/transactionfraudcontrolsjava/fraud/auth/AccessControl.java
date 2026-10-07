package com.github.shikejing.transactionfraudcontrolsjava.fraud.auth;

import java.util.Set;

/**
 * 权限校验：越权抛出带原因码的 AccessDeniedException。
 */
public interface AccessControl {

    void require(String operator, Role required);

    Set<Role> rolesOf(String operator);

    void grant(String operator, Role role);
}
