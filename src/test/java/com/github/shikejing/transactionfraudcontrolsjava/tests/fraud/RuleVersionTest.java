package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DecisionOutcome;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.Rule;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则版本化：受理时绑定版本，规则调整不改写历史判定，新旧版本并存行为明确。
 */
class RuleVersionTest {

    @TempDir
    Path dir;

    private Transaction riskyTx(String id) {
        // 全量信号：v1 得分 40 低于复核线 50 => 放行；v2 权重提高后得分 80 => 复核
        return new Transaction(id, "acct-v", "dev-v-" + id, "payee", 100, "CNY",
                System.currentTimeMillis(), Map.of(
                        "amountToAvgRatio", 6.0,
                        "deviceAccountCount", 1.0,
                        "txCountLastHour", 0.0,
                        "payeeRiskScore", 0.0));
    }

    @Test
    void acceptedTransactionsKeepBoundVersionAcrossRuleChange() {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);
        h.accessControl.grant("admin", Role.RULE_ADMIN);

        DecisionOutcome before = h.engine.submit(riskyTx("tx-before"), "submitter");
        assertEquals("v1", before.record().ruleVersion());
        assertEquals(DecisionType.ALLOW, before.record().decision());

        // 发布 v2：同一信号权重提高，复核线降低 => 同样的交易进入复核
        RuleSet v1 = h.currentRules();
        RuleSet v2 = new RuleSet("v2",
                List.of(new Rule("r-amount-high", "amountToAvgRatio", 5.0, 80, 5, "raised weight")),
                50.0, 100.0,
                v1.accountDailyLimitCents(), v1.deviceMaxTxPerWindow(), v1.deviceWindowMs(),
                System.currentTimeMillis());
        h.ruleRegistry.publish(v2);

        // 新交易按 v2 判定
        DecisionOutcome after = h.engine.submit(riskyTx("tx-after"), "submitter");
        assertEquals("v2", after.record().ruleVersion());
        assertEquals(DecisionType.REVIEW, after.record().decision());

        // 历史判定不被改写，仍按 v1 追溯
        DecisionRecord beforeRecord = h.engine.decisionOf("tx-before").orElseThrow();
        assertEquals("v1", beforeRecord.ruleVersion());
        assertEquals(DecisionType.ALLOW, beforeRecord.decision());
        assertEquals(before.record(), beforeRecord);

        // 重复提交旧交易：仍归并到 v1 的结论，不用新版本重判
        DecisionOutcome resubmit = h.engine.submit(riskyTx("tx-before"), "submitter");
        assertTrue(resubmit.duplicate());
        assertEquals("v1", resubmit.record().ruleVersion());
        assertEquals(DecisionType.ALLOW, resubmit.record().decision());

        // 历史版本可查
        assertTrue(h.ruleRegistry.byVersion("v1").isPresent());
        assertTrue(h.ruleRegistry.byVersion("v2").isPresent());
        assertEquals(2, h.ruleRegistry.allVersions().size());
    }

    @Test
    void sameSignalsUnderDifferentVersionsGiveDifferentButExplainableResults() {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);

        DecisionOutcome v1Outcome = h.engine.submit(riskyTx("tx-v1"), "submitter");
        RuleSet v1 = h.currentRules();
        h.ruleRegistry.publish(new RuleSet("v2",
                List.of(new Rule("r-amount-high", "amountToAvgRatio", 5.0, 80, 5, "raised")),
                50.0, 100.0, v1.accountDailyLimitCents(), v1.deviceMaxTxPerWindow(),
                v1.deviceWindowMs(), System.currentTimeMillis()));
        DecisionOutcome v2Outcome = h.engine.submit(riskyTx("tx-v2"), "submitter");

        // 相同信号在不同版本下结论不同，但各自可追溯到命中规则与观测值
        assertNotEquals(v1Outcome.record().decision(), v2Outcome.record().decision());
        assertEquals(40.0, v1Outcome.record().totalScore());
        assertEquals(80.0, v2Outcome.record().totalScore());
        assertFalse(v1Outcome.record().hits().isEmpty());
        assertFalse(v2Outcome.record().hits().isEmpty());
    }
}
