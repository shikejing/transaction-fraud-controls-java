package com.github.shikejing.transactionfraudcontrolsjava.fraud.api;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Transaction;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.FraudDecisionEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.SubmissionResult;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Loopback REST entry point for transaction submission and decision query.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final FraudDecisionEngine engine;
    private final IdempotencyStore idempotencyStore;

    public TransactionController(FraudDecisionEngine engine, IdempotencyStore idempotencyStore) {
        this.engine = engine;
        this.idempotencyStore = idempotencyStore;
    }

    public record TransactionRequest(String transactionId, String accountId, String deviceId,
                                     String payeeId, long amountCents, String currency,
                                     Long occurredAtEpochMs, Map<String, Double> signals) {
    }

    public record SubmissionResponse(DecisionRecord record, boolean duplicate) {
    }

    @PostMapping
    public SubmissionResponse submit(@RequestBody TransactionRequest request) {
        Transaction tx = new Transaction(
                request.transactionId(), request.accountId(), request.deviceId(),
                request.payeeId(), request.amountCents(),
                request.currency() == null ? "CNY" : request.currency(),
                request.occurredAtEpochMs() == null
                        ? System.currentTimeMillis() : request.occurredAtEpochMs(),
                request.signals());
        SubmissionResult result = engine.submit(tx);
        return new SubmissionResponse(result.record(), result.duplicate());
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<DecisionRecord> get(@PathVariable String transactionId) {
        return idempotencyStore.find(transactionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
