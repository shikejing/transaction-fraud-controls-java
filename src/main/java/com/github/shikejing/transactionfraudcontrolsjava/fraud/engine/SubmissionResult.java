package com.github.shikejing.transactionfraudcontrolsjava.fraud.engine;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;

/**
 * Result of submitting a transaction.
 *
 * @param duplicate true when this submission was folded into an earlier one
 */
public record SubmissionResult(DecisionRecord record, boolean duplicate) {
}
