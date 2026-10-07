package com.github.shikejing.transactionfraudcontrolsjava.fraud.api;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditEvent;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditEventType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditLog;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AccessDeniedException;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AuthorizationService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Operator;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.domain.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListEntry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListKind;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListKeyType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.persistence.JournalStore;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.review.ReviewRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.review.ReviewService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RiskRule;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSetVersion;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Loopback REST entry point for rule/list/review administration.
 * Callers identify themselves with {@code X-Operator-Id} and
 * {@code X-Operator-Roles} (comma-separated) headers; authorization is
 * enforced server-side and denials return 403 with a distinct reason.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RuleRegistry ruleRegistry;
    private final ListRegistry listRegistry;
    private final ReviewService reviewService;
    private final AuthorizationService authorizationService;
    private final AuditLog auditLog;
    private final JournalStore journalStore;

    public AdminController(RuleRegistry ruleRegistry, ListRegistry listRegistry,
                           ReviewService reviewService, AuthorizationService authorizationService,
                           AuditLog auditLog, JournalStore journalStore) {
        this.ruleRegistry = ruleRegistry;
        this.listRegistry = listRegistry;
        this.reviewService = reviewService;
        this.authorizationService = authorizationService;
        this.auditLog = auditLog;
        this.journalStore = journalStore;
    }

    public record RuleSetRequest(String version, List<RiskRule> rules,
                                 double reviewThreshold, double blockThreshold) {
    }

    public record ListEntryRequest(ListKeyType keyType, String keyValue, ListKind kind,
                                   String source, int priority, long expiresAtEpochMs) {
    }

    public record ReviewRequest(String transactionId, DecisionType outcome, String rationale) {
    }

    public record AppealResolveRequest(String transactionId, boolean release, String rationale) {
    }

    public record ErrorResponse(String error, String detail) {
    }

    @PostMapping("/rules")
    public RuleSetVersion publishRules(@RequestHeader("X-Operator-Id") String operatorId,
                                       @RequestHeader("X-Operator-Roles") String roles,
                                       @RequestBody RuleSetRequest request) {
        Operator operator = operator(operatorId, roles);
        authorizationService.require(operator, Role.RULE_MANAGER);
        RuleSetVersion version = ruleRegistry.publish(new RuleSetVersion(
                request.version(), request.rules(), request.reviewThreshold(),
                request.blockThreshold(), System.currentTimeMillis()));
        audit(operator.subjectId(), AuditEventType.RULE_PUBLISHED,
                "published rule version " + version.version());
        return version;
    }

    @GetMapping("/rules/current")
    public RuleSetVersion currentRules() {
        return ruleRegistry.current();
    }

    @GetMapping("/rules/{version}")
    public ResponseEntity<RuleSetVersion> ruleVersion(@PathVariable String version) {
        return ruleRegistry.get(version)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/lists")
    public ListEntry upsertListEntry(@RequestHeader("X-Operator-Id") String operatorId,
                                     @RequestHeader("X-Operator-Roles") String roles,
                                     @RequestBody ListEntryRequest request) {
        Operator operator = operator(operatorId, roles);
        authorizationService.require(operator, Role.LIST_MANAGER);
        ListEntry entry = new ListEntry(request.keyType(), request.keyValue(), request.kind(),
                request.source(), request.priority(), request.expiresAtEpochMs());
        listRegistry.upsert(entry);
        audit(operator.subjectId(), AuditEventType.LIST_CHANGED,
                "upsert " + entry.kind() + " " + entry.keyType() + "=" + entry.keyValue()
                        + " source=" + entry.source());
        return entry;
    }

    @PostMapping("/lists/remove")
    public void removeListEntry(@RequestHeader("X-Operator-Id") String operatorId,
                                @RequestHeader("X-Operator-Roles") String roles,
                                @RequestBody ListEntryRequest request) {
        Operator operator = operator(operatorId, roles);
        authorizationService.require(operator, Role.LIST_MANAGER);
        listRegistry.remove(request.keyType(), request.keyValue(), request.kind(), request.source());
        audit(operator.subjectId(), AuditEventType.LIST_CHANGED,
                "remove " + request.kind() + " " + request.keyType() + "=" + request.keyValue());
    }

    @GetMapping("/lists")
    public List<ListEntry> listSnapshot() {
        return listRegistry.snapshot();
    }

    @PostMapping("/reviews")
    public ReviewRecord decideReview(@RequestHeader("X-Operator-Id") String operatorId,
                                     @RequestHeader("X-Operator-Roles") String roles,
                                     @RequestBody ReviewRequest request) {
        return reviewService.decideReview(operator(operatorId, roles),
                request.transactionId(), request.outcome(), request.rationale());
    }

    @PostMapping("/appeals")
    public ReviewRecord fileAppeal(@RequestBody ReviewRequest request) {
        return reviewService.fileAppeal(request.transactionId(), request.rationale());
    }

    @PostMapping("/appeals/resolve")
    public ReviewRecord resolveAppeal(@RequestHeader("X-Operator-Id") String operatorId,
                                      @RequestHeader("X-Operator-Roles") String roles,
                                      @RequestBody AppealResolveRequest request) {
        return reviewService.resolveAppeal(operator(operatorId, roles),
                request.transactionId(), request.release(), request.rationale());
    }

    @GetMapping("/audit/transactions/{transactionId}")
    public List<AuditEvent> auditByTransaction(@PathVariable String transactionId) {
        return auditLog.byTransaction(transactionId);
    }

    @GetMapping("/audit/accounts/{accountId}")
    public List<AuditEvent> auditByAccount(@PathVariable String accountId) {
        return auditLog.byAccount(accountId);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> denied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("ACCESS_DENIED", e.getMessage()));
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(e.getClass().getSimpleName(), e.getMessage()));
    }

    private void audit(String operatorId, AuditEventType type, String detail) {
        AuditEvent event = new AuditEvent(type, operatorId, null, null,
                detail, System.currentTimeMillis());
        auditLog.append(event);
        journalStore.journalAudit(event);
    }

    private static Operator operator(String operatorId, String roles) {
        Set<Role> roleSet = roles == null || roles.isBlank()
                ? Set.of()
                : Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Role::valueOf)
                .collect(Collectors.toSet());
        return new Operator(operatorId, roleSet);
    }
}
