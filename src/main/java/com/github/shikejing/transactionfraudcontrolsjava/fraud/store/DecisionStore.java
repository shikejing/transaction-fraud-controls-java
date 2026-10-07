package com.github.shikejing.transactionfraudcontrolsjava.fraud.store;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;

import java.util.List;
import java.util.Optional;

/**
 * 判定记录存储：幂等写入，进程重启后可恢复。
 */
public interface DecisionStore {

    /** 已存在同 transactionId 的记录时返回已有记录（幂等归并）。 */
    DecisionRecord saveIfAbsent(DecisionRecord record);

    Optional<DecisionRecord> byTransactionId(String transactionId);

    List<DecisionRecord> byAccountId(String accountId);

    /** 恢复时扫描到的未完成判定。 */
    List<DecisionRecord> pendingRecords();
}
