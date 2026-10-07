package com.github.shikejing.transactionfraudcontrolsjava.fraud.engine;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;

/**
 * 一次受理的结果：record 为判定记录，duplicate 表示这是重复提交被归并。
 */
public record DecisionOutcome(DecisionRecord record, boolean duplicate) {
}
