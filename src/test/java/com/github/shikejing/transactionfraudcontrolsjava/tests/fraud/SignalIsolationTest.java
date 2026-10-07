package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DecisionOutcome;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.SignalEnricher;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.RuleHit;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单个信号源变慢/超时：按缺失处理，不阻塞其他交易判定，不拖垮整体吞吐。
 */
class SignalIsolationTest {

    @TempDir
    Path dir;

    @Test
    void slowSignalSourceTimesOutAndIsTreatedAsMissing() {
        // 一个永远睡死的信号源
        SignalEnricher stuck = new SignalEnricher() {
            @Override
            public String signalName() {
                return "payeeRiskScore";
            }

            @Override
            public Double enrich(Transaction transaction) throws Exception {
                Thread.sleep(60_000);
                return 0.99;
            }
        };
        FraudTestHarness h = FraudTestHarness.start(dir, List.of(stuck), 100);
        h.accessControl.grant("submitter", Role.SUBMITTER);

        Transaction tx = new Transaction("t-slow", "acct-s", "dev-s", "payee", 100, "CNY",
                System.currentTimeMillis(), Map.of(
                "amountToAvgRatio", 0.0, "deviceAccountCount", 0.0, "txCountLastHour", 0.0));

        long start = System.currentTimeMillis();
        DecisionOutcome outcome = h.engine.submit(tx, "submitter");
        long elapsed = System.currentTimeMillis() - start;

        // 判定在超时预算附近完成，不被慢信号源拖住
        assertTrue(elapsed < 5_000, "decision took too long: " + elapsed + "ms");
        // 超时信号按缺失处理（与观测为 0 区分），命中缺失计分 5
        RuleHit hit = outcome.record().hits().stream()
                .filter(r -> r.signal().equals("payeeRiskScore")).findFirst().orElseThrow();
        assertTrue(hit.signalMissing());
        assertNull(hit.observedValue());
        assertEquals(5.0, hit.scoreContribution());
        assertEquals(DecisionType.ALLOW, outcome.record().decision());

        // 其他交易不受影响，正常判定
        DecisionOutcome other = h.engine.submit(
                new Transaction("t-fast", "acct-f", "dev-f", "payee", 100, "CNY",
                        System.currentTimeMillis(), Map.of(
                        "amountToAvgRatio", 0.0, "deviceAccountCount", 0.0,
                        "txCountLastHour", 0.0, "payeeRiskScore", 0.0)),
                "submitter");
        assertEquals(DecisionType.ALLOW, other.record().decision());
    }
}
