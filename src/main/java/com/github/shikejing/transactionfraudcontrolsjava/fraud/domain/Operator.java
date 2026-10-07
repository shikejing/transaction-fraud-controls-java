package com.github.shikejing.transactionfraudcontrolsjava.fraud.domain;

import java.util.Set;

/**
 * An authenticated subject performing an operation.
 */
public record Operator(String subjectId, Set<Role> roles) {

    public Operator {
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(Role role) {
        return roles.contains(Role.ADMIN) || roles.contains(role);
    }
}
