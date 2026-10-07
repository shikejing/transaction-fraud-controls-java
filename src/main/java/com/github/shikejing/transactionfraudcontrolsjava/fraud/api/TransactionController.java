package com.github.shikejing.transactionfraudcontrolsjava.fraud.api;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DecisionOutcome;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.FraudEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 交易受理与判定查询（本地回环 HTTP）。
 */
@RestController
@RequestMapping("/api/fraud/transactions")
public class TransactionController {

    private final FraudEngine engine;

    public TransactionController(FraudEngine engine) {
        this.engine = engine;
    }

    @PostMapping
    public ResponseEntity<DecisionOutcome> submit(@RequestBody Transaction transaction,
                                                  @RequestHeader(value = "X-Operator", defaultValue = "anonymous") String operator) {
        return ResponseEntity.ok(engine.submit(transaction, operator));
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<DecisionRecord> decision(@PathVariable String transactionId) {
        return engine.decisionOf(transactionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-account/{accountId}")
    public List<DecisionRecord> byAccount(@PathVariable String accountId) {
        return engine.decisionsOfAccount(accountId);
    }
}
