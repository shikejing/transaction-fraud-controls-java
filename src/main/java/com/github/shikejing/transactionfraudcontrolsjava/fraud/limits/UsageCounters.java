package com.github.shikejing.transactionfraudcontrolsjava.fraud.limits;

/**
 * 额度与频率计数快照（用于判定与恢复后核对）。
 */
public record UsageCounters(
        long accountDailyAmountCents,
        int deviceTxCountInWindow) {
}
