package com.github.shikejing.transactionfraudcontrolsjava.fraud.rules;

import java.util.List;
import java.util.Optional;

/**
 * 规则版本注册表：保存全部历史版本，当前版本原子切换。
 */
public interface RuleRegistry {

    /** 当前生效版本（新交易受理时绑定）。 */
    RuleSet current();

    /** 按版本号查询历史规则集。 */
    Optional<RuleSet> byVersion(String version);

    /** 发布新版本，原子生效，旧版本仍可查询。 */
    RuleSet publish(RuleSet ruleSet);

    /** 全部版本（按发布时间升序）。 */
    List<RuleSet> allVersions();
}
