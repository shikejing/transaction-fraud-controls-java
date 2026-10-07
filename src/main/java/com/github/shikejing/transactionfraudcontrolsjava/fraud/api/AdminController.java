package com.github.shikejing.transactionfraudcontrolsjava.fraud.api;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AccessControl;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.lists.ListService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListEntry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleRegistry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.rules.RuleSet;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 规则与名单管理端点。所有变更要求 RULE_ADMIN 角色（X-Operator 头标识操作人），
 * 变更原子生效并写入审计。
 */
@RestController
@RequestMapping("/api/fraud/admin")
public class AdminController {

    private final RuleRegistry ruleRegistry;
    private final ListService listService;
    private final AccessControl accessControl;
    private final AuditService auditService;

    public AdminController(RuleRegistry ruleRegistry, ListService listService,
                           AccessControl accessControl, AuditService auditService) {
        this.ruleRegistry = ruleRegistry;
        this.listService = listService;
        this.accessControl = accessControl;
        this.auditService = auditService;
    }

    @PostMapping("/rules")
    public RuleSet publishRules(@RequestBody RuleSet ruleSet,
                                @RequestHeader("X-Operator") String operator) {
        accessControl.require(operator, Role.RULE_ADMIN);
        RuleSet published = ruleRegistry.publish(ruleSet);
        auditService.record("rules.publish", operator, null, null,
                "version=" + ruleSet.version());
        return published;
    }

    @GetMapping("/rules")
    public List<RuleSet> allRuleVersions() {
        return ruleRegistry.allVersions();
    }

    @GetMapping("/rules/{version}")
    public ResponseEntity<RuleSet> ruleVersion(@PathVariable String version) {
        return ruleRegistry.byVersion(version)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/lists")
    public Map<String, ListEntry> applyListChange(@RequestBody ListChangeRequest request,
                                                  @RequestHeader("X-Operator") String operator) {
        accessControl.require(operator, Role.RULE_ADMIN);
        var snapshot = listService.apply(request.additions(), request.removals());
        auditService.record("lists.change", operator, null, null,
                "additions=" + (request.additions() == null ? 0 : request.additions().size())
                        + " removals=" + (request.removals() == null ? 0 : request.removals().size()));
        return snapshot.entries();
    }

    @GetMapping("/lists")
    public Map<String, ListEntry> listSnapshot() {
        return listService.snapshot().entries();
    }

    @PostMapping("/grants")
    public void grant(@RequestBody GrantRequest request,
                      @RequestHeader("X-Operator") String operator) {
        accessControl.require(operator, Role.RULE_ADMIN);
        accessControl.grant(request.operator(), request.role());
        auditService.record("role.grant", operator, null, null,
                "granted " + request.role() + " to " + request.operator());
    }

    public record ListChangeRequest(List<ListEntry> additions, List<String> removals) {
    }

    public record GrantRequest(String operator, Role role) {
    }
}
