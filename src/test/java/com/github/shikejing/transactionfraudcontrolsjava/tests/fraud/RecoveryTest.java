package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DecisionOutcome;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.UsageCounters;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionStatus;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListEntry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListKind;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.FileJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重启恢复：已判定交易不丢失、拦截动作不重复执行；中断的判定可区分并被重驱；
 * 额度与频率计数恢复后不错乱。
 */
class RecoveryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dir;

    private Transaction tx(String id, String account, long amountCents) {
        return new Transaction(id, account, "dev-" + id, "payee", amountCents, "CNY",
                System.currentTimeMillis(), Map.of(
                "amountToAvgRatio", 0.0, "deviceAccountCount", 0.0,
                "txCountLastHour", 0.0, "payeeRiskScore", 0.0));
    }

    @Test
    void completedDecisionsSurviveRestartWithoutReexecution() {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);
        h.listService.apply(List.of(new ListEntry("acct-block", ListKind.BLOCK, "police", 100, 0)),
                List.of());

        h.engine.submit(tx("tx-ok", "acct-ok", 100_00), "submitter");
        h.engine.submit(tx("tx-blocked", "acct-block", 200_00), "submitter");
        long completedBefore = h.auditService.byTransaction("tx-blocked").stream()
                .filter(e -> e.eventType().equals("decision.completed")).count();
        assertEquals(1, completedBefore);

        // 重启
        FraudTestHarness h2 = h.restart();

        // 已判定结果不丢失
        assertEquals(DecisionType.ALLOW, h2.engine.decisionOf("tx-ok").orElseThrow().decision());
        assertEquals(DecisionType.BLOCK, h2.engine.decisionOf("tx-blocked").orElseThrow().decision());

        // 重试已判定交易：归并返回，不重复触发拦截动作
        DecisionOutcome retry = h2.engine.submit(tx("tx-blocked", "acct-block", 200_00), "submitter");
        assertTrue(retry.duplicate());
        long completedAfter = h2.auditService.byTransaction("tx-blocked").stream()
                .filter(e -> e.eventType().equals("decision.completed")).count();
        assertEquals(1, completedAfter, "block action must not be re-executed after restart");

        // 额度计数恢复一致：放行的 100_00 仍在账上，被拦截的未占用
        UsageCounters counters = h2.limitService.snapshot("acct-ok", "dev-tx-ok",
                System.currentTimeMillis(), h2.currentRules());
        assertEquals(100_00, counters.accountDailyAmountCents());

        // 名单与规则版本同样恢复
        assertTrue(h2.listService.snapshot().lookup("acct-block", System.currentTimeMillis()).isPresent());
        assertEquals("v1", h2.currentRules().version());
    }

    @Test
    void interruptedDecisionIsDistinguishableAndRedrivenOnRecovery() throws Exception {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);

        // 模拟崩溃：交易已受理（tx 日志 + PENDING 记录 + 额度预留），判定未完成
        Transaction crashed = tx("tx-crash", "acct-crash", 300_00);
        FileJournal txJournal = new FileJournal(dir, "transactions.log");
        txJournal.append("tx-accepted", mapper.writeValueAsString(crashed));
        FileJournal decisionJournal = new FileJournal(dir, "decisions.log");
        DecisionRecord pending = new DecisionRecord(
                crashed.transactionId(), crashed.accountId(), crashed.deviceId(), crashed.payeeId(),
                crashed.amountCents(), DecisionStatus.PENDING, null, 0d, "v1",
                List.of(), List.of("accepted, decision pending"),
                System.currentTimeMillis(), 0L);
        decisionJournal.append("decision", mapper.writeValueAsString(pending));
        FileJournal limitJournal = new FileJournal(dir, "limits.log");
        limitJournal.append("limit-acquire", mapper.writeValueAsString(Map.of(
                "transactionId", crashed.transactionId(),
                "accountId", crashed.accountId(),
                "deviceId", crashed.deviceId(),
                "amountCents", crashed.amountCents(),
                "atEpochMs", System.currentTimeMillis())));

        // 重启恢复
        FraudTestHarness h2 = h.restart();

        // 中断的判定被重驱为已完成，且审计事件可区分（decision.recovered）
        DecisionRecord recovered = h2.engine.decisionOf("tx-crash").orElseThrow();
        assertEquals(DecisionStatus.COMPLETED, recovered.status());
        assertEquals(DecisionType.ALLOW, recovered.decision());
        assertEquals("v1", recovered.ruleVersion(), "按受理时绑定的版本重驱");
        assertTrue(h2.auditService.byTransaction("tx-crash").stream()
                .anyMatch(e -> e.eventType().equals("decision.recovered")));

        // 崩溃前的额度预留不重复扣减：300_00 只计一次
        UsageCounters counters = h2.limitService.snapshot("acct-crash", "dev-tx-crash",
                System.currentTimeMillis(), h2.currentRules());
        assertEquals(300_00, counters.accountDailyAmountCents());
    }

    @Test
    void dispositionsSurviveRestart() {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);
        h.accessControl.grant("dispo-admin", Role.DISPOSITION_ADMIN);
        h.listService.apply(List.of(new ListEntry("acct-x", ListKind.BLOCK, "police", 100, 0)),
                List.of());
        h.engine.submit(tx("tx-x", "acct-x", 100), "submitter");
        h.engine.dispose("tx-x", com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Disposition.DispositionAction.RELEASE_BLOCK,
                "dispo-admin", "false positive");

        FraudTestHarness h2 = h.restart();
        // 解除拦截的处置在重启后依然生效
        assertEquals(DecisionType.ALLOW, h2.engine.effectiveDecisionOf("tx-x"));
        // 原始判定仍是拦截（不被改写）
        assertEquals(DecisionType.BLOCK, h2.engine.decisionOf("tx-x").orElseThrow().decision());
    }
}
