package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.Role;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.engine.DecisionOutcome;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.DecisionType;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListEntry;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.ListKind;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.model.Transaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 名单：拦截直接拦截、观察进入复核；来源优先级与过期；变更原子生效。
 */
class WatchlistTest {

    @TempDir
    Path dir;

    private Transaction tx(String id, String account, String device, String payee) {
        return new Transaction(id, account, device, payee, 100, "CNY",
                System.currentTimeMillis(), Map.of(
                "amountToAvgRatio", 0.0, "deviceAccountCount", 0.0,
                "txCountLastHour", 0.0, "payeeRiskScore", 0.0));
    }

    @Test
    void blockListBlocksAndWatchListReviews() {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);
        long never = 0;

        h.listService.apply(List.of(
                new ListEntry("bad-acct", ListKind.BLOCK, "police", 100, never),
                new ListEntry("watch-dev", ListKind.WATCH, "internal", 10, never)), List.of());

        DecisionOutcome blocked = h.engine.submit(tx("t1", "bad-acct", "dev-x", "payee"), "submitter");
        assertEquals(DecisionType.BLOCK, blocked.record().decision());
        assertTrue(blocked.record().reasons().stream().anyMatch(r -> r.contains("block list hit")));

        DecisionOutcome watched = h.engine.submit(tx("t2", "ok-acct", "watch-dev", "payee"), "submitter");
        assertEquals(DecisionType.REVIEW, watched.record().decision());
        assertTrue(watched.record().reasons().stream().anyMatch(r -> r.contains("watch list hit")));

        DecisionOutcome clean = h.engine.submit(tx("t3", "ok-acct", "dev-y", "payee"), "submitter");
        assertEquals(DecisionType.ALLOW, clean.record().decision());
    }

    @Test
    void listChangeIsAtomicForSubsequentTransactions() {
        FraudTestHarness h = FraudTestHarness.start(dir);
        h.accessControl.grant("submitter", Role.SUBMITTER);

        // 一次变更同时加入两条：生效前交易放行，生效后交易拦截，不存在半新半旧
        assertTrue(h.listService.snapshot().lookup("a1", now()).isEmpty());
        assertTrue(h.listService.snapshot().lookup("a2", now()).isEmpty());

        h.listService.apply(List.of(
                new ListEntry("a1", ListKind.BLOCK, "src", 1, 0),
                new ListEntry("a2", ListKind.BLOCK, "src", 1, 0)), List.of());

        var snapshot = h.listService.snapshot();
        assertTrue(snapshot.lookup("a1", now()).isPresent());
        assertTrue(snapshot.lookup("a2", now()).isPresent());

        assertEquals(DecisionType.BLOCK,
                h.engine.submit(tx("t1", "a1", "d1", "p"), "submitter").record().decision());
        assertEquals(DecisionType.BLOCK,
                h.engine.submit(tx("t2", "a2", "d2", "p"), "submitter").record().decision());

        // 移除同样原子生效
        h.listService.apply(List.of(), List.of("a1", "a2"));
        assertTrue(h.listService.snapshot().lookup("a1", now()).isEmpty());
        assertEquals(DecisionType.ALLOW,
                h.engine.submit(tx("t3", "a1", "d3", "p"), "submitter").record().decision());
    }

    @Test
    void sourcePriorityAndExpiryAreRespected() {
        FraudTestHarness h = FraudTestHarness.start(dir);

        // 低优先级来源先写入，高优先级来源覆盖
        h.listService.apply(List.of(
                new ListEntry("k", ListKind.WATCH, "internal", 10, 0)), List.of());
        h.listService.apply(List.of(
                new ListEntry("k", ListKind.BLOCK, "police", 100, 0)), List.of());
        Optional<ListEntry> entry = h.listService.snapshot().lookup("k", now());
        assertTrue(entry.isPresent());
        assertEquals(ListKind.BLOCK, entry.get().kind());
        assertEquals("police", entry.get().source());

        // 低优先级来源的反向变更不能覆盖高优先级条目
        h.listService.apply(List.of(
                new ListEntry("k", ListKind.WATCH, "internal", 10, 0)), List.of());
        assertEquals(ListKind.BLOCK, h.listService.snapshot().lookup("k", now()).orElseThrow().kind());

        // 过期条目不再生效
        long past = System.currentTimeMillis() - 1000;
        h.listService.apply(List.of(
                new ListEntry("expired-key", ListKind.BLOCK, "police", 100, past)), List.of());
        assertTrue(h.listService.snapshot().lookup("expired-key", now()).isEmpty());
    }

    private long now() {
        return System.currentTimeMillis();
    }
}
