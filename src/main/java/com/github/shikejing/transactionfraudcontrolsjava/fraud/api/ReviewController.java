package com.github.shikejing.transactionfraudcontrolsjava.fraud.api;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DefaultFraudEngine;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Disposition;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.store.JournalBackedDispositionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 复核与申诉端点：处置只追加记录，不改写原始判定。
 */
@RestController
@RequestMapping("/api/fraud/review")
public class ReviewController {

    private final DefaultFraudEngine engine;
    private final JournalBackedDispositionStore dispositionStore;

    public ReviewController(DefaultFraudEngine engine, JournalBackedDispositionStore dispositionStore) {
        this.engine = engine;
        this.dispositionStore = dispositionStore;
    }

    @PostMapping("/{transactionId}")
    public Disposition dispose(@PathVariable String transactionId,
                               @RequestBody DisposeRequest request,
                               @RequestHeader("X-Operator") String operator) {
        return engine.dispose(transactionId, request.action(), operator, request.reason());
    }

    @GetMapping("/{transactionId}")
    public ResponseEntity<ReviewView> view(@PathVariable String transactionId) {
        return engine.decisionOf(transactionId).map(record -> {
            DecisionType effective = engine.effectiveDecisionOf(transactionId);
            List<Disposition> dispositions = dispositionStore.of(transactionId);
            return ResponseEntity.ok(new ReviewView(record, effective, dispositions));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record DisposeRequest(Disposition.DispositionAction action, String reason) {
    }

    /** 原始判定 + 当前生效结论 + 全部处置记录。 */
    public record ReviewView(DecisionRecord original, DecisionType effective,
                             List<Disposition> dispositions) {
    }
}
