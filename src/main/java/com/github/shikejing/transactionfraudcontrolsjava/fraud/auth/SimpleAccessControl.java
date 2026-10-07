package com.github.shikejing.transactionfraudcontrolsjava.fraud.auth;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内角色注册与校验。越权抛出带可区分原因码的 AccessDeniedException。
 */
public class SimpleAccessControl implements AccessControl {

    public static final String REASON_UNKNOWN_OPERATOR = "UNKNOWN_OPERATOR";
    public static final String REASON_MISSING_ROLE = "MISSING_ROLE";

    private final Map<String, Set<Role>> roles = new ConcurrentHashMap<>();

    @Override
    public void require(String operator, Role required) {
        Set<Role> owned = roles.get(operator);
        if (owned == null) {
            throw new AccessDeniedException(REASON_UNKNOWN_OPERATOR,
                    "operator '" + operator + "' has no registered roles");
        }
        if (!owned.contains(required)) {
            throw new AccessDeniedException(REASON_MISSING_ROLE,
                    "operator '" + operator + "' lacks required role " + required);
        }
    }

    @Override
    public Set<Role> rolesOf(String operator) {
        return roles.getOrDefault(operator, Set.of());
    }

    @Override
    public void grant(String operator, Role role) {
        roles.compute(operator, (k, v) -> {
            if (v == null) {
                return Set.of(role);
            }
            Set<Role> next = new java.util.HashSet<>(v);
            next.add(role);
            return Set.copyOf(next);
        });
    }
}
