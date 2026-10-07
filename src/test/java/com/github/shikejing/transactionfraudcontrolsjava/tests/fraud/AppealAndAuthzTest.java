package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AccessDeniedException;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.SimpleAccessControl;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DecisionOutcome;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionRecord;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Disposition;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListEntry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListKind;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 权限与申诉：越权被拒绝且原因可区分；复核不改写原判定；误拦有可解释的解除路径。
 */
class AppealAndAuthzTest {

    @TempDir
    Path dir;

    private Transaction tx(String id, String account) {
        return new Transaction(id, account, "dev-" + id, "payee", 100, "CNY",
                System.currentTimeMillis(), Map.of(
                "amountToAvgRatio", 0.0, "deviceAccountCount", 0.0,
                "txCountLastHour", 0.0, "payeeRiskScore", 0.0));
    }

    private FraudTestHarness harnessWithRoles() {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);
        h.accessControl.grant("reviewer", Role.REVIEWER);
        h.accessControl.grant("dispo-admin", Role.DISPOSITION_ADMIN);
        return h;
    }

    @Test
    void unauthorizedOperationsAreRejectedWithDistinctReasons() {
        FraudTestHarness h = harnessWithRoles();

        // 未注册主体：UNKNOWN_OPERATOR
        AccessDeniedException unknown = assertThrows(AccessDeniedException.class,
                () -> h.engine.submit(tx("t1", "acct"), "nobody"));
        assertEquals(SimpleAccessControl.REASON_UNKNOWN_OPERATOR, unknown.reasonCode());

        // 已注册但缺角色：MISSING_ROLE
        AccessDeniedException missing = assertThrows(AccessDeniedException.class,
                () -> h.accessControl.require("submitter", Role.RULE_ADMIN));
        assertEquals(SimpleAccessControl.REASON_MISSING_ROLE, missing.reasonCode());

        // 复核角色不能解除拦截（需要 DISPOSITION_ADMIN）
        h.listService.apply(List.of(new ListEntry("acct-b", ListKind.BLOCK, "police", 100, 0)), List.of());
        h.engine.submit(tx("t2", "acct-b"), "submitter");
        AccessDeniedException wrongRole = assertThrows(AccessDeniedException.class,
                () -> h.engine.dispose("t2", Disposition.DispositionAction.RELEASE_BLOCK,
                        "reviewer", "trying"));
        assertEquals(SimpleAccessControl.REASON_MISSING_ROLE, wrongRole.reasonCode());
    }

    @Test
    void falseBlockAppealReleasesWithoutRewritingOriginalDecision() {
        FraudTestHarness h = harnessWithRoles();
        h.listService.apply(List.of(new ListEntry("acct-c", ListKind.BLOCK, "fraud-team", 50, 0)), List.of());

        DecisionOutcome blocked = h.engine.submit(tx("t3", "acct-c"), "submitter");
        assertEquals(DecisionType.BLOCK, blocked.record().decision());

        // 误拦申诉：授权角色解除拦截
        Disposition release = h.engine.dispose("t3", Disposition.DispositionAction.RELEASE_BLOCK,
                "dispo-admin", "verified legitimate customer");
        assertEquals("dispo-admin", release.operator());

        // 原始判定记录不被改写
        DecisionRecord original = h.engine.decisionOf("t3").orElseThrow();
        assertEquals(DecisionType.BLOCK, original.decision());
        assertEquals(blocked.record(), original);

        // 生效结论变为放行，解除路径可解释（处置记录携带操作人与依据）
        assertEquals(DecisionType.ALLOW, h.engine.effectiveDecisionOf("t3"));
        assertTrue(release.reason().contains("verified legitimate customer"));

        // 重复解除被拒绝（状态冲突，结果确定）
        assertThrows(IllegalStateException.class,
                () -> h.engine.dispose("t3", Disposition.DispositionAction.RELEASE_BLOCK,
                        "dispo-admin", "again"));

        // 审计可追溯：判定与解除都留痕
        var trail = h.auditService.byTransaction("t3");
        assertTrue(trail.stream().anyMatch(e -> e.eventType().equals("decision.completed")));
        assertTrue(trail.stream().anyMatch(e -> e.eventType().equals("disposition.RELEASE_BLOCK")));
    }

    @Test
    void reviewConfirmKeepsOriginalAndEscalateBlocks() {
        FraudTestHarness h = harnessWithRoles();
        h.listService.apply(List.of(new ListEntry("acct-w", ListKind.WATCH, "internal", 10, 0)), List.of());

        DecisionOutcome reviewed = h.engine.submit(tx("t4", "acct-w"), "submitter");
        assertEquals(DecisionType.REVIEW, reviewed.record().decision());

        // 复核确认：维持原判定
        h.engine.dispose("t4", Disposition.DispositionAction.CONFIRM, "reviewer", "looks fine");
        assertEquals(DecisionType.REVIEW, h.engine.effectiveDecisionOf("t4"));

        // 复核升级为拦截：生效结论变化，原记录不变
        h.engine.dispose("t4", Disposition.DispositionAction.ESCALATE_TO_BLOCK, "reviewer",
                "confirmed fraud pattern");
        assertEquals(DecisionType.BLOCK, h.engine.effectiveDecisionOf("t4"));
        assertEquals(DecisionType.REVIEW, h.engine.decisionOf("t4").orElseThrow().decision());

        // 申诉与处置并发时按锁串行：重复升级被拒绝，结果确定
        assertThrows(IllegalStateException.class,
                () -> h.engine.dispose("t4", Disposition.DispositionAction.ESCALATE_TO_BLOCK,
                        "reviewer", "again"));
    }
}
