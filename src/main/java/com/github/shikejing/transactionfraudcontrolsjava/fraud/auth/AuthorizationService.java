package com.github.shikejing.transactionfraudcontrolsjava.fraud.auth;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Operator;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Role;
import org.springframework.stereotype.Component;

/**
 * Role-based authorization checks. Denials throw AccessDeniedException
 * carrying the missing role, so callers can distinguish the reason.
 */
@Component
public class AuthorizationService {

    /** Require the operator to hold the role; throws AccessDeniedException otherwise. */
    public void require(Operator operator, Role role) {
        if (operator == null || !operator.hasRole(role)) {
            throw new AccessDeniedException(
                    operator == null ? "<anonymous>" : operator.subjectId(), role);
        }
    }
}
