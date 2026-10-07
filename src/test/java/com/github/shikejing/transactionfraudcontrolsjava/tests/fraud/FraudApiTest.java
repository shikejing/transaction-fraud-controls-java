package com.github.shikejing.transactionfraudcontrolsjava.tests.fraud;

import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.AccessControl;
import com.github.shikejing.transactionfraudcontrolsjava.fraud.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地回环 HTTP 端到端：提交交易、重复提交归并、越权拒绝、审计追溯。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FraudApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AccessControl accessControl;

    private static final java.nio.file.Path DATA_DIR = createDataDir();

    private static java.nio.file.Path createDataDir() {
        try {
            return java.nio.file.Files.createTempDirectory("fraud-api-test");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("fraud.data-dir", () -> DATA_DIR.toString());
    }

    @BeforeEach
    void grantRoles() {
        accessControl.grant("api-submitter", Role.SUBMITTER);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body, String operator) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        if (operator != null) {
            headers.add("X-Operator", operator);
        }
        return new HttpEntity<>(body, headers);
    }

    private Map<String, Object> txBody(String id) {
        return Map.of(
                "transactionId", id,
                "accountId", "acct-api",
                "deviceId", "dev-api",
                "payeeId", "payee-api",
                "amountCents", 100,
                "currency", "CNY",
                "occurredAtEpochMs", System.currentTimeMillis(),
                "signals", Map.of("amountToAvgRatio", 0.0, "deviceAccountCount", 0.0,
                        "txCountLastHour", 0.0, "payeeRiskScore", 0.0));
    }

    @Test
    void submitDecideAndQueryOverLoopback() {
        // 越权：未注册主体提交被拒绝，原因码可区分
        ResponseEntity<Map> denied = rest.exchange(url("/api/fraud/transactions"),
                HttpMethod.POST, json(txBody("api-tx-0"), "stranger"), Map.class);
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        assertEquals("UNKNOWN_OPERATOR", denied.getBody().get("reasonCode"));

        // 授权提交：放行
        ResponseEntity<Map> ok = rest.exchange(url("/api/fraud/transactions"),
                HttpMethod.POST, json(txBody("api-tx-1"), "api-submitter"), Map.class);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals("ALLOW", ((Map<?, ?>) ok.getBody().get("record")).get("decision"));
        assertEquals(false, ok.getBody().get("duplicate"));

        // 重复提交：归并
        ResponseEntity<Map> dup = rest.exchange(url("/api/fraud/transactions"),
                HttpMethod.POST, json(txBody("api-tx-1"), "api-submitter"), Map.class);
        assertEquals(true, dup.getBody().get("duplicate"));

        // 判定查询
        ResponseEntity<Map> decision = rest.getForEntity(
                url("/api/fraud/transactions/api-tx-1"), Map.class);
        assertEquals(HttpStatus.OK, decision.getStatusCode());
        assertEquals("v1", decision.getBody().get("ruleVersion"));

        // 审计追溯
        ResponseEntity<Object[]> audit = rest.getForEntity(
                url("/api/fraud/audit/by-transaction/api-tx-1"), Object[].class);
        assertTrue(audit.getBody().length >= 1);
    }
}
