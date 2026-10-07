package com.github.shikejing.transactionfraudcontrolsjava.fraud.lists;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListEntry;

import java.util.Map;
import java.util.Optional;

/**
 * 名单的不可变快照：一次判定只读一个快照，保证不会读到半新半旧的状态。
 */
public interface ListSnapshot {

    /** 查询某键当前生效的条目（按来源优先级裁决、剔除过期）。 */
    Optional<ListEntry> lookup(String key, long nowEpochMs);

    Map<String, ListEntry> entries();
}
