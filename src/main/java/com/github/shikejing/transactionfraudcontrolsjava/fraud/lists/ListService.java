package com.github.shikejing.transactionfraudcontrolsjava.fraud.lists;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListEntry;

import java.util.Collection;

/**
 * 名单服务：变更对后续交易原子生效。
 */
public interface ListService {

    ListSnapshot snapshot();

    /** 原子地应用一批新增/移除，返回新快照。 */
    ListSnapshot apply(Collection<ListEntry> additions, Collection<String> removals);
}
