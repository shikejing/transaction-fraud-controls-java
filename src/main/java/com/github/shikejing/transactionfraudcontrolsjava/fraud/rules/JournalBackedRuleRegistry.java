package com.github.shikejing.transactionfraudcontrolsjava.fraud.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.Journal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 规则注册表：版本不可变，发布后写入日志持久化；当前版本原子切换。
 * 已受理交易绑定受理时的版本，新版本不影响历史判定。
 */
public class JournalBackedRuleRegistry implements RuleRegistry {

    static final String EVENT_TYPE = "ruleset";

    private final Journal journal;
    private final ObjectMapper mapper;
    private final Map<String, RuleSet> versions = new LinkedHashMap<>();
    private final AtomicReference<RuleSet> current;

    public JournalBackedRuleRegistry(Journal journal, ObjectMapper mapper, RuleSet bootstrap) {
        this.journal = journal;
        this.mapper = mapper;
        this.current = new AtomicReference<>(bootstrap);
        versions.put(bootstrap.version(), bootstrap);
    }

    public void recover() {
        for (Journal.Entry entry : journal.replay()) {
            if (!EVENT_TYPE.equals(entry.type())) {
                continue;
            }
            try {
                RuleSet ruleSet = mapper.readValue(entry.payloadJson(), RuleSet.class);
                versions.put(ruleSet.version(), ruleSet);
                current.set(ruleSet);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
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
        try {
            journal.append(EVENT_TYPE, mapper.writeValueAsString(ruleSet));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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
