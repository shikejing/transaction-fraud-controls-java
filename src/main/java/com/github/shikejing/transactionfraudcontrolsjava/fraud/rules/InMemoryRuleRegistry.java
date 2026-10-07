package com.github.shikejing.transactionfraudcontrolsjava.fraud.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存规则注册表：当前版本用 AtomicReference 原子切换，
 * 历史版本不可变、永久可查；已受理交易绑定的是版本快照，不受切换影响。
 */
public class InMemoryRuleRegistry implements RuleRegistry {

    private final Map<String, RuleSet> versions = new LinkedHashMap<>();
    private final AtomicReference<RuleSet> current = new AtomicReference<>();

    public InMemoryRuleRegistry(RuleSet initial) {
        versions.put(initial.version(), initial);
        current.set(initial);
    }

    @Override
    public RuleSet current() {
        return current.get();
    }

    @Override
    public Optional<RuleSet> byVersion(String version) {
        return Optional.ofNullable(versions.get(version));
    }

    @Override
    public synchronized RuleSet publish(RuleSet ruleSet) {
        if (versions.containsKey(ruleSet.version())) {
            throw new IllegalArgumentException("rule version already exists: " + ruleSet.version());
        }
        versions.put(ruleSet.version(), ruleSet);
        current.set(ruleSet);
        return ruleSet;
    }

    @Override
    public synchronized List<RuleSet> allVersions() {
        return new ArrayList<>(versions.values());
    }
}
