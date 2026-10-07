package com.github.shikejing.transactionfraudcontrolsjava.fraud.api;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.audit.AuditService;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.AuditEvent;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 审计追溯端点：按交易或账号查询全部操作留痕。
 */
@RestController
@RequestMapping("/api/fraud/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/by-transaction/{transactionId}")
    public List<AuditEvent> byTransaction(@PathVariable String transactionId) {
        return auditService.byTransaction(transactionId);
    }

    @GetMapping("/by-account/{accountId}")
    public List<AuditEvent> byAccount(@PathVariable String accountId) {
        return auditService.byAccount(accountId);
    }
}
