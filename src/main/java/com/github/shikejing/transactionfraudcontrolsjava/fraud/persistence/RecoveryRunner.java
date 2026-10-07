package com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.idempotency.IdempotencyStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.limits.VelocityLimiter;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.review.ReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Replays the local journal on startup so completed decisions, review
 * outcomes, limit usage and audit events survive a process restart.
 */
@Component
public class RecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecoveryRunner.class);

    private final JournalStore journalStore;
    private final IdempotencyStore idempotencyStore;
    private final ReviewService reviewService;
    private final AuditLog auditLog;
    private final VelocityLimiter limiter;

    public RecoveryRunner(JournalStore journalStore,
                          IdempotencyStore idempotencyStore,
                          ReviewService reviewService,
                          AuditLog auditLog,
                          VelocityLimiter limiter) {
        this.journalStore = journalStore;
        this.idempotencyStore = idempotencyStore;
        this.reviewService = reviewService;
        this.auditLog = auditLog;
        this.limiter = limiter;
    }

    @Override
    public void run(ApplicationArguments args) {
        journalStore.recover(idempotencyStore, reviewService, auditLog, limiter);
        log.info("fraud journal recovery completed");
    }
}
