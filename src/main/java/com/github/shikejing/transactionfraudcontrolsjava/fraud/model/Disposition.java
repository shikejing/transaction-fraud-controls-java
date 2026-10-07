package com.github.shikejing.transactionfraudcontrolsjava.fraud.model;

/**
 * 复核/申诉产生的处置记录：追加而非改写原始判定。
 */
public record Disposition(
        String dispositionId,
        String transactionId,
        DispositionAction action,
        String operator,
        String reason,
        long createdAtEpochMs,
        long basedOnDecisionVersion) {

    public enum DispositionAction {
        /** 复核确认维持原判定 */
        CONFIRM,
        /** 复核升级：放行改拦截等 */
        ESCALATE_TO_BLOCK,
        /** 解除拦截（误拦申诉通过） */
        RELEASE_BLOCK
    }
}
