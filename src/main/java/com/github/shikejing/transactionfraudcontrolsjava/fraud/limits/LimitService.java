package com.github.shikejing.transactionfraudcontrolsjava.fraud.limits;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;

/**
 * 额度/频率约束：同一账号或设备的并发交易必须收敛到唯一结果，不得穿透。
 * 预留按 transactionId 幂等：重复提交或中断重放不会重复扣减。
 */
public interface LimitService {

    /**
     * 尝试为账号/设备预留额度与频率配额。
     * 同一 transactionId 重复调用返回首次结果（幂等）。
     */
    LimitDecision tryAcquire(String transactionId, String accountId, String deviceId,
                             long amountCents, long nowEpochMs, RuleSet limits);

    /** 判定最终未放行（如被拦截）时按 transactionId 归还预留。 */
    void release(String transactionId, long nowEpochMs);

    UsageCounters snapshot(String accountId, String deviceId, long nowEpochMs, RuleSet limits);

    record LimitDecision(boolean acquired, String reason) {
    }
}
