package com.github.shikejing.transactionfraudcontrolsjava.fraud.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.JournalBackedAuditService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AccessControl;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.SimpleAccessControl;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DefaultFraudEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.FraudEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.SignalEnricher;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.DefaultLimitService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.LimitService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.AtomicListServiceImpl;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.JournalBackedRuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.Rule;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.DefaultRiskScorer;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.RiskScorer;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.FileJournal;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.JournalBackedDecisionStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.JournalBackedDispositionStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * 欺诈判定能力装配：全部组件进程内运行，状态落在本地日志目录，启动时重放恢复。
 */
@Configuration
@EnableConfigurationProperties(FraudProperties.class)
public class FraudConfiguration {

    @Bean
    public RiskScorer riskScorer() {
        return new DefaultRiskScorer();
    }

    @Bean
    public AccessControl accessControl() {
        return new SimpleAccessControl();
    }

    @Bean
    public JournalBackedRuleRegistry ruleRegistry(FraudProperties properties, ObjectMapper mapper) {
        JournalBackedRuleRegistry registry = new JournalBackedRuleRegistry(
                new FileJournal(Path.of(properties.getDataDir()), "rules.log"), mapper, bootstrapRuleSet());
        registry.recover();
        return registry;
    }

    @Bean
    public JournalBackedDecisionStore decisionStore(FraudProperties properties, ObjectMapper mapper) {
        JournalBackedDecisionStore store = new JournalBackedDecisionStore(
                new FileJournal(Path.of(properties.getDataDir()), "decisions.log"), mapper);
        store.recover();
        return store;
    }

    @Bean
    public JournalBackedDispositionStore dispositionStore(FraudProperties properties, ObjectMapper mapper) {
        JournalBackedDispositionStore store = new JournalBackedDispositionStore(
                new FileJournal(Path.of(properties.getDataDir()), "dispositions.log"), mapper);
        store.recover();
        return store;
    }

    @Bean
    public DefaultLimitService limitService(FraudProperties properties, ObjectMapper mapper) {
        DefaultLimitService service = new DefaultLimitService(
                new FileJournal(Path.of(properties.getDataDir()), "limits.log"), mapper);
        service.recover();
        return service;
    }

    @Bean
    public AtomicListServiceImpl listService(FraudProperties properties, ObjectMapper mapper) {
        AtomicListServiceImpl service = new AtomicListServiceImpl(
                new FileJournal(Path.of(properties.getDataDir()), "lists.log"), mapper);
        service.recover();
        return service;
    }

    @Bean
    public JournalBackedAuditService auditService(FraudProperties properties, ObjectMapper mapper) {
        JournalBackedAuditService service = new JournalBackedAuditService(
                new FileJournal(Path.of(properties.getDataDir()), "audit.log"), mapper);
        service.recover();
        return service;
    }

    @Bean
    public DefaultFraudEngine fraudEngine(JournalBackedDecisionStore decisionStore,
                                          JournalBackedDispositionStore dispositionStore,
                                          LimitService limitService,
                                          ListService listService,
                                          RuleRegistry ruleRegistry,
                                          RiskScorer riskScorer,
                                          AuditService auditService,
                                          AccessControl accessControl,
                                          FraudProperties properties,
                                          ObjectMapper mapper,
                                          List<SignalEnricher> enrichers) {
        DefaultFraudEngine engine = new DefaultFraudEngine(decisionStore, dispositionStore,
                limitService, listService, ruleRegistry, riskScorer, auditService, accessControl,
                new FileJournal(Path.of(properties.getDataDir()), "transactions.log"),
                mapper, enrichers, properties.getSignalTimeoutMs());
        // 启动恢复：重驱中断时未完成的判定
        engine.recover();
        return engine;
    }

    /** 内置初始规则版本 v1：后续可通过管理端点发布新版本，旧版本永久可查。 */
    public static RuleSet bootstrapRuleSet() {
        return new RuleSet(
                "v1",
                List.of(
                        new Rule("r-amount-high", "amountToAvgRatio", 5.0, 40, 5,
                                "transaction amount far above account average"),
                        new Rule("r-device-shared", "deviceAccountCount", 3.0, 30, 10,
                                "device shared by many accounts"),
                        new Rule("r-velocity", "txCountLastHour", 10.0, 30, 0,
                                "high transaction frequency in last hour"),
                        new Rule("r-payee-risk", "payeeRiskScore", 0.8, 40, 5,
                                "payee with high historical risk score")),
                50.0,   // reviewThreshold
                100.0,  // blockThreshold
                1_000_000L, // accountDailyLimitCents = 10,000.00
                5,          // deviceMaxTxPerWindow
                60_000L,    // deviceWindowMs = 1min
                System.currentTimeMillis());
    }
}
