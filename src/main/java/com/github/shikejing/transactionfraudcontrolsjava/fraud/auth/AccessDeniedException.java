package com.github.shikejing.transactionfraudcontrolsjava.fraud.auth;

/**
 * 越权操作异常：携带可区分的原因码。
 */
public class AccessDeniedException extends RuntimeException {

    private final String reasonCode;

    public AccessDeniedException(String reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
