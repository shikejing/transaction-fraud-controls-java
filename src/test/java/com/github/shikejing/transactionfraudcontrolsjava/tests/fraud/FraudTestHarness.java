package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.JournalBackedAuditService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.SimpleAccessControl;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.config.FraudConfiguration;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DefaultFraudEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.SignalEnricher;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.DefaultLimitService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.AtomicListServiceImpl;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.JournalBackedRuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.DefaultRiskScorer;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.FileJournal;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.JournalBackedDecisionStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.JournalBackedDispositionStore;

import java.nio.file.Path;
import java.util.List;

/**
 * 测试装配：与生产配置相同的组件图，但数据目录指向临时目录。
 * restart() 在同一目录上重建全部组件，模拟进程重启。
 */
class FraudTestHarness {

    final Path dir;
    final ObjectMapper mapper = new ObjectMapper();
    JournalBackedRuleRegistry ruleRegistry;
    JournalBackedDecisionStore decisionStore;
    JournalBackedDispositionStore dispositionStore;
    DefaultLimitService limitService;
    AtomicListServiceImpl listService;
    JournalBackedAuditService auditService;
    SimpleAccessControl accessControl;
    DefaultFraudEngine engine;

    private final List<SignalEnricher> enrichers;
    private final long signalTimeoutMs;

    private FraudTestHarness(Path dir, List<SignalEnricher> enrichers, long signalTimeoutMs) {
        this.dir = dir;
        this.enrichers = enrichers;
        this.signalTimeoutMs = signalTimeoutMs;
    }

    static FraudTestHarness start(Path dir) {
        return start(dir, List.of(), 200);
    }

    static FraudTestHarness start(Path dir, List<SignalEnricher> enrichers, long signalTimeoutMs) {
        FraudTestHarness harness = new FraudTestHarness(dir, enrichers, signalTimeoutMs);
        harness.boot();
        return harness;
    }

    /** 模拟进程重启：同一数据目录上重建全部组件并恢复。 */
    FraudTestHarness restart() {
        FraudTestHarness next = new FraudTestHarness(dir, enrichers, signalTimeoutMs);
        next.accessControl = this.accessControl; // 角色注册保留（进程内权限模型）
        next.boot();
        return next;
    }

    private void boot() {
        if (accessControl == null) {
            accessControl = new SimpleAccessControl();
        }
        ruleRegistry = new JournalBackedRuleRegistry(
                new FileJournal(dir, "rules.log"), mapper, FraudConfiguration.bootstrapRuleSet());
        ruleRegistry.recover();
        decisionStore = new JournalBackedDecisionStore(
                new FileJournal(dir, "decisions.log"), mapper);
        decisionStore.recover();
        dispositionStore = new JournalBackedDispositionStore(
                new FileJournal(dir, "dispositions.log"), mapper);
        dispositionStore.recover();
        limitService = new DefaultLimitService(new FileJournal(dir, "limits.log"), mapper);
        limitService.recover();
        listService = new AtomicListServiceImpl(new FileJournal(dir, "lists.log"), mapper);
        listService.recover();
        auditService = new JournalBackedAuditService(new FileJournal(dir, "audit.log"), mapper);
        auditService.recover();
        engine = new DefaultFraudEngine(decisionStore, dispositionStore, limitService, listService,
                ruleRegistry, new DefaultRiskScorer(), auditService, accessControl,
                new FileJournal(dir, "transactions.log"), mapper, enrichers, signalTimeoutMs);
        engine.recover();
    }

    RuleSet currentRules() {
        return ruleRegistry.current();
    }
}
