package com.github.shikejing.transactionfraudcontrolsjava.fraud.auth;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Role;

/**
 * Thrown when a subject lacks the required role. Carries a machine-readable
 * reason so denials are distinguishable.
 */
public class AccessDeniedException extends RuntimeException {

    private final String subjectId;
    private final Role requiredRole;

    public AccessDeniedException(String subjectId, Role requiredRole) {
        super("subject '" + subjectId + "' lacks required role " + requiredRole);
        this.subjectId = subjectId;
        this.requiredRole = requiredRole;
    }

    public String subjectId() {
        return subjectId;
    }

    public Role requiredRole() {
        return requiredRole;
    }
}
