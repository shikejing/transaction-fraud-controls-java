package com.github.shikejing.transactionfraudcontrolsjava.fraud.rules;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds all published rule set versions. The currently active version is
 * swapped atomically; historical versions remain queryable forever so past
 * decisions can always be traced to the exact rules that produced them.
 * While two versions coexist (one bound by in-flight transactions, one
 * active for new ones), each transaction keeps the version captured at
 * acceptance time.
 */
@Component
public class RuleRegistry {

    private final Map<String, RuleSetVersion> versions = new ConcurrentHashMap<>();
    private final AtomicReference<RuleSetVersion> current = new AtomicReference<>();

    public RuleRegistry() {
        // Built-in bootstrap version so the engine is usable out of the box.
        publish(new RuleSetVersion(
                "v1",
                java.util.List.of(
                        new RiskRule("R-HIGH-AMOUNT", "amountCents", 500_000, 40, "single large transaction"),
                        new RiskRule("R-NEW-DEVICE", "deviceFirstSeenDays", 1, 25, "device first seen recently"),
                        new RiskRule("R-VELOCITY-1H", "accountTxCount1h", 10, 20, "high account frequency"),
                        new RiskRule("R-NEW-PAYEE", "payeeFirstSeenDays", 1, 15, "payee first seen recently")),
                30.0,
                60.0,
                0L));
    }

    /** The version active right now; transactions accepted now bind to it. */
    public RuleSetVersion current() {
        return current.get();
    }

    /** Look up a historical version for traceability. */
    public Optional<RuleSetVersion> get(String version) {
        return Optional.ofNullable(versions.get(version));
    }

    /**
     * Publish a new immutable version and activate it atomically.
     * Re-publishing an existing version id is rejected to keep history stable.
     */
    public synchronized RuleSetVersion publish(RuleSetVersion ruleSet) {
        if (versions.putIfAbsent(ruleSet.version(), ruleSet) != null) {
            throw new IllegalStateException("rule version already exists: " + ruleSet.version());
        }
        current.set(ruleSet);
        return ruleSet;
    }
}
