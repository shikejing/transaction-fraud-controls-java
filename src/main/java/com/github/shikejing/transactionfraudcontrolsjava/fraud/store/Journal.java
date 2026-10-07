package com.github.shikejing.transactionfraudcontrolsjava.fraud.store;

import java.io.Closeable;
import java.util.List;

/**
 * 追加式本地日志：所有状态变更先落盘后生效，重启时重放恢复。
 */
public interface Journal extends Closeable {

    void append(String type, String payloadJson);

    /** 重启时按写入顺序重放。 */
    List<Entry> replay();

    record Entry(String type, String payloadJson) {
    }
}
