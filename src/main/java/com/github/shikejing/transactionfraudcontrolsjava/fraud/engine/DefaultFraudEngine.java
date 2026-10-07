package com.github.shikejing.transactionfraudcontrolsjava.fraud.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AccessControl;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.LimitService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListSnapshot;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionStatus;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Disposition;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListEntry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListKind;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.RiskScorer;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.ScoreResult;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.Journal;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.JournalBackedDecisionStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.JournalBackedDispositionStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 判定引擎默认实现。
 *
 * 关键语义：
 * - 同一 transactionId 的提交经分条锁串行化，重复提交/网络重试命中已完成的判定直接归并返回；
 * - 受理时绑定当时生效的规则版本并先落 PENDING 记录与交易日志，再执行判定；
 * - 判定完成写 COMPLETED 记录；进程中断后 PENDING 记录与已提交结果可区分，
 *   恢复时用受理日志中的原始交易按当时绑定的规则版本重驱；
 * - 名单读取只用一个原子快照，一次判定内不会读到半新半旧状态；
 * - 信号源以超时隔离调用，慢信号源按缺失处理，不阻塞判定。
 */
public class DefaultFraudEngine implements FraudEngine {

    static final String EVENT_TX_ACCEPTED = "tx-accepted";

    private static final int LOCK_STRIPES = 1024;

    private final JournalBackedDecisionStore decisionStore;
    private final JournalBackedDispositionStore dispositionStore;
    private final LimitService limitService;
    private final ListService listService;
    private final RuleRegistry ruleRegistry;
    private final RiskScorer scorer;
    private final AuditService auditService;
    private final AccessControl accessControl;
    private final Journal transactionJournal;
    private final ObjectMapper mapper;
    private final List<SignalEnricher> enrichers;
    private final long signalTimeoutMs;
    private final ExecutorService signalExecutor;
    private final ReentrantLock[] txLocks = new ReentrantLock[LOCK_STRIPES];

    public DefaultFraudEngine(JournalBackedDecisionStore decisionStore,
                              JournalBackedDispositionStore dispositionStore,
                              LimitService limitService,
                              ListService listService,
                              RuleRegistry ruleRegistry,
                              RiskScorer scorer,
                              AuditService auditService,
                              AccessControl accessControl,
                              Journal transactionJournal,
                              ObjectMapper mapper,
                              List<SignalEnricher> enrichers,
                              long signalTimeoutMs) {
        this.decisionStore = decisionStore;
        this.dispositionStore = dispositionStore;
        this.limitService = limitService;
        this.listService = listService;
        this.ruleRegistry = ruleRegistry;
        this.scorer = scorer;
        this.auditService = auditService;
        this.accessControl = accessControl;
        this.transactionJournal = transactionJournal;
        this.mapper = mapper;
        this.enrichers = enrichers == null ? List.of() : List.copyOf(enrichers);
        this.signalTimeoutMs = signalTimeoutMs;
        this.signalExecutor = Executors.newCachedThreadPool();
        for (int i = 0; i < LOCK_STRIPES; i++) {
            txLocks[i] = new ReentrantLock();
        }
    }

    @Override
    public DecisionOutcome submit(Transaction transaction, String operator) {
        accessControl.require(operator, Role.SUBMITTER);
        ReentrantLock lock = lockFor(transaction.transactionId());
        lock.lock();
        try {
            Optional<DecisionRecord> existing = decisionStore.byTransactionId(transaction.transactionId());
            if (existing.isPresent() && existing.get().status() == DecisionStatus.COMPLETED) {
                auditService.record("decision.duplicate", operator, transaction.transactionId(),
                        transaction.accountId(), "duplicate submission merged into existing decision");
                return new DecisionOutcome(existing.get(), true);
            }
            RuleSet ruleSet;
            if (existing.isPresent()) {
                // 中断恢复/重试：沿用受理时绑定的规则版本
                ruleSet = ruleRegistry.byVersion(existing.get().ruleVersion())
                        .orElseGet(ruleRegistry::current);
            } else {
                ruleSet = ruleRegistry.current();
                accept(transaction, ruleSet);
            }
            DecisionRecord completed = decideAndComplete(transaction, ruleSet, operator, "decision.completed");
            return new DecisionOutcome(completed, false);
        } finally {
            lock.unlock();
        }
    }

    /** 受理：先持久化原始交易与 PENDING 记录，再进入判定。 */
    private void accept(Transaction transaction, RuleSet ruleSet) {
        try {
            transactionJournal.append(EVENT_TX_ACCEPTED, mapper.writeValueAsString(transaction));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        long now = System.currentTimeMillis();
        DecisionRecord pending = new DecisionRecord(
                transaction.transactionId(), transaction.accountId(), transaction.deviceId(),
                transaction.payeeId(), transaction.amountCents(),
                DecisionStatus.PENDING, null, 0d, ruleSet.version(),
                List.of(), List.of("accepted, decision pending"), now, 0L);
        decisionStore.saveIfAbsent(pending);
    }

    private DecisionRecord decideAndComplete(Transaction transaction, RuleSet ruleSet,
                                             String operator, String auditEvent) {
        long now = System.currentTimeMillis();
        Transaction enriched = enrich(transaction);
        ListSnapshot lists = listService.snapshot();
        List<String> reasons = new ArrayList<>();

        DecisionType decision = null;

        // 1. 名单：拦截名单直接拦截，观察名单进入复核
        Optional<ListEntry> blockHit = firstListHit(enriched, lists, ListKind.BLOCK, now);
        Optional<ListEntry> watchHit = firstListHit(enriched, lists, ListKind.WATCH, now);
        if (blockHit.isPresent()) {
            ListEntry e = blockHit.get();
            reasons.add("block list hit: key=" + e.key() + " source=" + e.source()
                    + " priority=" + e.priority());
            decision = DecisionType.BLOCK;
        }

        // 2. 评分（相同信号 + 相同规则版本 => 逐项一致）
        ScoreResult score = scorer.score(enriched, ruleSet);

        // 3. 额度/频率：未命中拦截名单才占用配额；拦截不消耗额度
        boolean reserved = false;
        if (decision == null) {
            LimitService.LimitDecision limit = limitService.tryAcquire(
                    enriched.transactionId(), enriched.accountId(), enriched.deviceId(),
                    enriched.amountCents(), now, ruleSet);
            if (!limit.acquired()) {
                reasons.add("limit rejected: " + limit.reason());
                decision = DecisionType.BLOCK;
            } else {
                reserved = true;
            }
        }

        // 4. 阈值合成
        if (decision == null) {
            if (score.totalScore() >= ruleSet.blockThreshold()) {
                reasons.add("score " + score.totalScore() + " >= block threshold "
                        + ruleSet.blockThreshold());
                decision = DecisionType.BLOCK;
            } else if (watchHit.isPresent()) {
                ListEntry e = watchHit.get();
                reasons.add("watch list hit: key=" + e.key() + " source=" + e.source());
                decision = DecisionType.REVIEW;
            } else if (score.totalScore() >= ruleSet.reviewThreshold()) {
                reasons.add("score " + score.totalScore() + " >= review threshold "
                        + ruleSet.reviewThreshold());
                decision = DecisionType.REVIEW;
            } else {
                reasons.add("score " + score.totalScore() + " below review threshold "
                        + ruleSet.reviewThreshold());
                decision = DecisionType.ALLOW;
            }
        }

        // 拦截不占用额度；复核保留占用等待处置
        if (decision == DecisionType.BLOCK && reserved) {
            limitService.release(enriched.transactionId(), now);
            reserved = false;
        }

        DecisionRecord completed = new DecisionRecord(
                enriched.transactionId(), enriched.accountId(), enriched.deviceId(),
                enriched.payeeId(), enriched.amountCents(),
                DecisionStatus.COMPLETED, decision, score.totalScore(), ruleSet.version(),
                score.hits(), List.copyOf(reasons),
                decisionStore.byTransactionId(enriched.transactionId())
                        .map(DecisionRecord::acceptedAtEpochMs).orElse(now),
                now);
        decisionStore.complete(completed);
        auditService.record(auditEvent, operator, enriched.transactionId(), enriched.accountId(),
                "decision=" + decision + " score=" + score.totalScore()
                        + " ruleVersion=" + ruleSet.version() + " reasons=" + reasons);
        return completed;
    }

    /** 信号增强：每个信号源限时调用，超时/失败按缺失处理，不阻塞整体判定。 */
    private Transaction enrich(Transaction transaction) {
        if (enrichers.isEmpty()) {
            return transaction;
        }
        Map<String, Double> signals = new HashMap<>(
                transaction.signals() == null ? Map.of() : transaction.signals());
        for (SignalEnricher enricher : enrichers) {
            if (signals.containsKey(enricher.signalName())) {
                continue;
            }
            Future<Double> future = signalExecutor.submit(() -> enricher.enrich(transaction));
            try {
                Double value = future.get(signalTimeoutMs, TimeUnit.MILLISECONDS);
                if (value != null) {
                    signals.put(enricher.signalName(), value);
                }
            } catch (TimeoutException e) {
                future.cancel(true);
                // 信号缺失：保持缺失，与观测为 0 区分
            } catch (Exception e) {
                future.cancel(true);
            }
        }
        return new Transaction(transaction.transactionId(), transaction.accountId(),
                transaction.deviceId(), transaction.payeeId(), transaction.amountCents(),
                transaction.currency(), transaction.occurredAtEpochMs(), Map.copyOf(signals));
    }

    private Optional<ListEntry> firstListHit(Transaction tx, ListSnapshot snapshot,
                                             ListKind kind, long now) {
        for (String key : List.of(tx.accountId(), tx.deviceId(), tx.payeeId())) {
            Optional<ListEntry> entry = snapshot.lookup(key, now);
            if (entry.isPresent() && entry.get().kind() == kind) {
                return entry;
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<DecisionRecord> decisionOf(String transactionId) {
        return decisionStore.byTransactionId(transactionId);
    }

    @Override
    public List<DecisionRecord> decisionsOfAccount(String accountId) {
        return decisionStore.byAccountId(accountId);
    }

    /** 交易当前生效的判定：原始判定 + 最新处置的合成视图，原记录不被改写。 */
    public DecisionType effectiveDecisionOf(String transactionId) {
        DecisionRecord record = decisionStore.byTransactionId(transactionId)
                .orElseThrow(() -> new NoSuchElementException("unknown transaction " + transactionId));
        Optional<Disposition> latest = dispositionStore.latestOf(transactionId);
        if (latest.isEmpty()) {
            return record.decision();
        }
        return switch (latest.get().action()) {
            case CONFIRM -> record.decision();
            case ESCALATE_TO_BLOCK -> DecisionType.BLOCK;
            case RELEASE_BLOCK -> DecisionType.ALLOW;
        };
    }

    @Override
    public Disposition dispose(String transactionId, Disposition.DispositionAction action,
                               String operator, String reason) {
        // 角色校验：复核与解除拦截需要不同授权
        switch (action) {
            case CONFIRM, ESCALATE_TO_BLOCK -> accessControl.require(operator, Role.REVIEWER);
            case RELEASE_BLOCK -> accessControl.require(operator, Role.DISPOSITION_ADMIN);
        }
        ReentrantLock lock = lockFor(transactionId);
        lock.lock();
        try {
            DecisionRecord record = decisionStore.byTransactionId(transactionId)
                    .orElseThrow(() -> new NoSuchElementException("unknown transaction " + transactionId));
            if (record.status() != DecisionStatus.COMPLETED) {
                throw new IllegalStateException("decision not completed yet: " + transactionId);
            }
            DecisionType effective = effectiveDecisionOf(transactionId);
            long now = System.currentTimeMillis();
            switch (action) {
                case ESCALATE_TO_BLOCK -> {
                    if (effective == DecisionType.BLOCK) {
                        throw new IllegalStateException("already blocked: " + transactionId);
                    }
                    limitService.release(transactionId, now);
                }
                case RELEASE_BLOCK -> {
                    if (effective != DecisionType.BLOCK) {
                        throw new IllegalStateException("not blocked, nothing to release: " + transactionId);
                    }
                    // 误拦解除：尝试补占额度，结果写入处置依据，行为确定可解释
                    LimitService.LimitDecision reacquire = limitService.tryAcquire(
                            transactionId, record.accountId(), record.deviceId(),
                            record.amountCents(), now, ruleRegistry.byVersion(record.ruleVersion())
                                    .orElseGet(ruleRegistry::current));
                    reason = reason + " [quota re-acquire: " + reacquire.reason() + "]";
                }
                case CONFIRM -> {
                    // 维持原判定，无额度变动
                }
            }
            Disposition disposition = new Disposition(UUID.randomUUID().toString(), transactionId,
                    action, operator, reason, now, record.decidedAtEpochMs());
            dispositionStore.append(disposition);
            auditService.record("disposition." + action, operator, transactionId,
                    record.accountId(), "reason=" + reason);
            return disposition;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 重启恢复：重放受理日志，对没有 COMPLETED 判定的交易按受理时绑定的规则版本重驱。
     * 额度预留按 transactionId 幂等，重驱不会重复扣减。
     */
    public void recover() {
        Map<String, Transaction> accepted = new LinkedHashMapTx();
        for (Journal.Entry entry : transactionJournal.replay()) {
            if (!EVENT_TX_ACCEPTED.equals(entry.type())) {
                continue;
            }
            try {
                Transaction tx = mapper.readValue(entry.payloadJson(), Transaction.class);
                accepted.put(tx.transactionId(), tx);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        for (Transaction tx : accepted.values()) {
            Optional<DecisionRecord> record = decisionStore.byTransactionId(tx.transactionId());
            if (record.isPresent() && record.get().status() == DecisionStatus.COMPLETED) {
                continue;
            }
            RuleSet ruleSet = record
                    .flatMap(r -> ruleRegistry.byVersion(r.ruleVersion()))
                    .orElseGet(ruleRegistry::current);
            if (record.isEmpty()) {
                accept(tx, ruleSet);
            }
            decideAndComplete(tx, ruleSet, "system-recovery", "decision.recovered");
        }
    }

    private ReentrantLock lockFor(String transactionId) {
        return txLocks[(transactionId.hashCode() & 0x7fffffff) % LOCK_STRIPES];
    }

    private static final class LinkedHashMapTx extends java.util.LinkedHashMap<String, Transaction> {
    }
}
