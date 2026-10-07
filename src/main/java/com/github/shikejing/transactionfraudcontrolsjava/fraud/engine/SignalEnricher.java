package com.github.shikejing.transactionfraudcontrolsjava.fraud.engine;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;

/**
 * 信号源：为交易补充一个本地信号。实现可能很慢或失败，
 * 引擎会以超时隔离调用，超时的信号按“缺失”处理，不阻塞其他交易。
 */
public interface SignalEnricher {

    String signalName();

    Double enrich(Transaction transaction) throws Exception;
}
