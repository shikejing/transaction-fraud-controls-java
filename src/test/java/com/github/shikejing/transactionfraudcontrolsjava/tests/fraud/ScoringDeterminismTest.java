package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.config.FraudConfiguration;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.RuleHit;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.DefaultRiskScorer;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.ScoreResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评分口径：相同信号 + 相同规则版本 => 逐项一致；信号缺失与信号为零区别对待。
 */
class ScoringDeterminismTest {

    private final DefaultRiskScorer scorer = new DefaultRiskScorer();
    private final RuleSet rules = FraudConfiguration.bootstrapRuleSet();

    private Transaction tx(Map<String, Double> signals) {
        return new Transaction("t1", "acct", "dev", "payee", 1000, "CNY",
                System.currentTimeMillis(), signals);
    }

    @Test
    void sameSignalsSameVersionProduceIdenticalBreakdown() {
        Map<String, Double> signals = Map.of(
                "amountToAvgRatio", 6.0,
                "deviceAccountCount", 1.0,
                "txCountLastHour", 12.0,
                "payeeRiskScore", 0.1);
        ScoreResult first = scorer.score(tx(signals), rules);
        ScoreResult second = scorer.score(tx(signals), rules);
        assertEquals(first.totalScore(), second.totalScore());
        assertEquals(first.hits(), second.hits());
        // 逐项命中明细可追溯：规则、观测值、阈值、贡献
        for (RuleHit hit : first.hits()) {
            assertNotNull(hit.ruleId());
            assertNotNull(hit.observedValue());
            assertFalse(hit.signalMissing());
        }
        // amountToAvgRatio 6>=5 贡献 40，txCountLastHour 12>=10 贡献 30，其余 0
        assertEquals(70.0, first.totalScore());
    }

    @Test
    void missingSignalDiffersFromZeroSignal() {
        ScoreResult missing = scorer.score(tx(Map.of()), rules);
        ScoreResult zero = scorer.score(tx(Map.of(
                "amountToAvgRatio", 0.0,
                "deviceAccountCount", 0.0,
                "txCountLastHour", 0.0,
                "payeeRiskScore", 0.0)), rules);

        // 缺失按 missingSignalScore 计分（5+10+0+5=20），为零按阈值比较全部不命中（0）
        assertEquals(20.0, missing.totalScore());
        assertEquals(0.0, zero.totalScore());

        RuleHit missingHit = missing.hits().stream()
                .filter(h -> h.ruleId().equals("r-amount-high")).findFirst().orElseThrow();
        assertTrue(missingHit.signalMissing());
        assertNull(missingHit.observedValue());

        RuleHit zeroHit = zero.hits().stream()
                .filter(h -> h.ruleId().equals("r-amount-high")).findFirst().orElseThrow();
        assertFalse(zeroHit.signalMissing());
        assertEquals(0.0, zeroHit.observedValue());
    }
}
