package com.github.shikejing.transactionfraudcontrolsjava.fraud.limits;

/**
 * Velocity/amount constraint for one dimension (account or device) over a
 * sliding window.
 */
public record LimitPolicy(
        long maxAmountCentsPerWindow,
        int maxCountPerWindow,
        long windowMillis) {
}
