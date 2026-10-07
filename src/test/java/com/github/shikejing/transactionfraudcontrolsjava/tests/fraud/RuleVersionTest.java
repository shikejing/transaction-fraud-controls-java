package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.config.FraudProperties;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.FraudDecisionEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.VelocityLimiter;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence.JournalStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RiskRule;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSetVersion;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.scoring.RiskScorer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleVersionTest {

    private FraudDecisionEngine newEngine(RuleRegistry rules) {
        FraudProperties props = new FraudProperties();
        return new FraudDecisionEngine(new IdempotencyStore(), rules, new ListRegistry(),
                new RiskScorer(), new VelocityLimiter(), new AuditLog(),
                new JournalStore(props), props);
    }

    private Transaction tx(String id) {
        return new Transaction(id, "acct-v", "dev-v", "payee-v", 100, "CNY",
                System.currentTimeMillis(), Map.of("accountTxCount1h", 15.0));
    }

    @Test
    void acceptedTransactionsKeepTheirRuleVersion() {
        RuleRegistry rules = new RuleRegistry();
        FraudDecisionEngine engine = newEngine(rules);

        // v1: R-VELOCITY-1H weight 20, review threshold 30 -> 15 >= 10 fires,
        // score 20 < 30 -> ALLOW.
        DecisionRecord before = engine.submit(tx("tx-before")).record();
        assertEquals("v1", before.ruleVersion());
        assertEquals(DecisionType.ALLOW, before.decision());

        // Publish v2 with a much heavier weight: same signal now BLOCKs.
        rules.publish(new RuleSetVersion("v2",
                List.of(new RiskRule("R-VELOCITY-1H", "accountTxCount1h", 10, 100, "stricter")),
                30.0, 60.0, System.currentTimeMillis()));

        DecisionRecord after = engine.submit(tx("tx-after")).record();
        assertEquals("v2", after.ruleVersion());
        assertEquals(DecisionType.BLOCK, after.decision());

        // Historical decision is NOT rewritten by the rule change and stays
        // traceable to the version that produced it.
        DecisionRecord replayed = engine.submit(tx("tx-before")).record();
        assertEquals("v1", replayed.ruleVersion());
        assertEquals(DecisionType.ALLOW, replayed.decision());
        assertTrue(rules.get("v1").isPresent());
        assertEquals(rules.get("v1").orElseThrow().rules().size(),
                before.ruleHits().size());
    }

    @Test
    void republishingSameVersionIsRejected() {
        RuleRegistry rules = new RuleRegistry();
        assertThrows(IllegalStateException.class, () -> rules.publish(new RuleSetVersion(
                "v1", List.of(), 1, 2, System.currentTimeMillis())));
    }
}
