package com.github.shikejing.transactionfraudcontrolsjava.fraud.engine;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Disposition;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;

import java.util.List;
import java.util.Optional;

/**
 * 判定引擎：受理交易、归并重复、合成判定、处置申诉。
 */
public interface FraudEngine {

    /** 受理并判定一笔交易；重复提交归并到同一次判定。 */
    DecisionOutcome submit(Transaction transaction, String operator);

    Optional<DecisionRecord> decisionOf(String transactionId);

    List<DecisionRecord> decisionsOfAccount(String accountId);

    /** 复核/申诉处置：追加处置记录，不改写原判定。 */
    Disposition dispose(String transactionId, Disposition.DispositionAction action,
                        String operator, String reason);
}
