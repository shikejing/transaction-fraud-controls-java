package com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditEvent;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.config.FraudProperties;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.VelocityLimiter;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.review.ReviewRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.review.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Append-only local-file journal for restart recovery. Completed decisions,
 * review records, limit usage and audit events are journaled (one JSON
 * object per line) so a restart loses nothing and never re-executes block
 * actions. Decisions that were in-flight at crash time never reach the
 * journal, so they stay distinguishable from committed results and are
 * re-decidable on resubmission. When no journal directory is configured
 * the store degrades to a no-op (pure in-memory mode).
 */
@Component
public class JournalStore {

    private static final Logger log = LoggerFactory.getLogger(JournalStore.class);
    private static final String JOURNAL_FILE = "fraud-journal.jsonl";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path journalPath;
    private BufferedWriter writer;

    public JournalStore(FraudProperties properties) {
        if (properties.getJournalDir() == null || properties.getJournalDir().isBlank()) {
            this.journalPath = null;
        } else {
            try {
                Path dir = Path.of(properties.getJournalDir());
                Files.createDirectories(dir);
                this.journalPath = dir.resolve(JOURNAL_FILE);
                this.writer = Files.newBufferedWriter(journalPath,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private synchronized void append(String type, Object payload) {
        if (writer == null) {
            return;
        }
        try {
            var node = mapper.createObjectNode();
            node.put("type", type);
            node.set("payload", mapper.valueToTree(payload));
            writer.write(mapper.writeValueAsString(node));
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void journalDecision(DecisionRecord record) {
        append("decision", record);
    }

    public void journalReview(ReviewRecord record) {
        append("review", record);
    }

    public void journalLimitUsage(String key, long amountCents, long atEpochMs) {
        append("limit", new LimitUsageEntry(key, amountCents, atEpochMs));
    }

    public void journalAudit(AuditEvent event) {
        append("audit", event);
    }

    public record LimitUsageEntry(String key, long amountCents, long atEpochMs) {
    }

    /**
     * Replay the journal into the live components. Completed decisions are
     * restored as already-decided (so retries fold, no re-execution of
     * block actions); limit usage is re-applied so amount/frequency
     * counters are consistent after restart.
     */
    public void recover(IdempotencyStore idempotencyStore, ReviewService reviewService,
                        AuditLog auditLog, VelocityLimiter limiter) {
        if (journalPath == null || !Files.exists(journalPath)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(journalPath);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = mapper.readTree(line);
                String type = node.get("type").asText();
                JsonNode payload = node.get("payload");
                switch (type) {
                    case "decision" -> idempotencyStore.restore(
                            mapper.treeToValue(payload, DecisionRecord.class));
                    case "review" -> reviewService.restore(
                            mapper.treeToValue(payload, ReviewRecord.class));
                    case "limit" -> {
                        LimitUsageEntry entry = mapper.treeToValue(payload, LimitUsageEntry.class);
                        limiter.recordUsage(entry.key(), entry.amountCents(), entry.atEpochMs());
                    }
                    case "audit" -> auditLog.append(mapper.treeToValue(payload, AuditEvent.class));
                    default -> log.warn("Ignoring unknown journal entry type: {}", type);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
