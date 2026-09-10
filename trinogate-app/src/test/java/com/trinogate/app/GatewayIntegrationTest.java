package com.trinogate.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.trinogate.config.ConfigLoader;
import com.trinogate.config.GatewayConfig;
import com.trinogate.server.GatewayApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test: a fake Trino coordinator behind a real gateway instance.
 * Covers pass-through, two-page polling, validation rejection, metadata allow,
 * rate limiting (429 + Retry-After), admin dynamic rules, cancellation and
 * metadata endpoints.
 */
class GatewayIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UTF8 = StandardCharsets.UTF_8.name();

    private static HttpServer backend;
    private static int backendPort;
    private static final AtomicInteger statementHits = new AtomicInteger();

    private static GatewayApplication gateway;
    private static String gatewayBase;
    private static final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void setUp() throws Exception {
        startBackend();
        startGateway();
    }

    @AfterAll
    static void tearDown() {
        gateway.close();
        backend.stop(0);
    }

    private static void startBackend() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backendPort = backend.getAddress().getPort();
        backend.createContext("/v1/statement", GatewayIntegrationTest::handleStatement);
        backend.createContext("/v1/statement/executing/", GatewayIntegrationTest::handleNextPage);
        backend.createContext("/v1/info", ex -> {
            String body = "{\"nodeVersion\":{\"version\":\"trino-446\"},\"environment\":\"test\",\"starting\":false}";
            sendJson(ex, body);
        });
        backend.start();
    }

    private static void startGateway() throws Exception {
        int port = freePort();
        Path rulesDir = Files.createTempDirectory("trinogate-rules");
        Path configPath = Files.createTempFile("trinogate-test", ".yaml");
        String yaml = """
                server:
                  httpPort: %d
                  gatewayBaseUri: "http://127.0.0.1:%d"
                auth:
                  type: header
                  headerName: X-Auth-User
                validation:
                  policy: REJECT
                  rulesPath: "%s"
                  rulesPollSeconds: 60
                  selectRequireWhereOrLimit: true
                  exemptNoFrom: true
                  allowMetadataStatements: true
                flow:
                  rateLimit:
                    mode: fixedWindow
                    defaultPerMinute: 1000
                    perUser: { limited: 3 }
                  queue:
                    maxGlobalPending: 100
                    maxPerUserPending: 20
                    timeoutSeconds: 5
                  concurrency:
                    maxRunningGlobal: 50
                    maxRunningPerUser: 10
                routing:
                  strategy: roundRobin
                  healthCheck: { enabled: false }
                  clusters:
                    - { name: fake, url: "http://127.0.0.1:%d", active: true }
                proxy:
                  connectTimeoutMs: 3000
                  readTimeoutMs: 8000
                  idleTimeoutSeconds: 30
                """.formatted(port, port, rulesDir.toString().replace("\\", "\\\\"), backendPort);
        Files.writeString(configPath, yaml);
        GatewayConfig config = ConfigLoader.load(configPath);
        gateway = GatewayApplication.start(config);
        gatewayBase = "http://127.0.0.1:" + gateway.actualPort();
    }

    // ---- fake backend handlers ----

    private static void handleStatement(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        String sql = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        int n = statementHits.incrementAndGet();
        String qid = "backend-" + n;
        String body;
        if (sql.contains("SLOW")) {
            body = page(qid, null, "http://127.0.0.1:" + backendPort + "/v1/statement/executing/" + qid + "/1");
        } else {
            body = page(qid, "[[1]]", null);
        }
        sendJson(ex, body);
    }

    private static void handleNextPage(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String qid = path.substring("/v1/statement/executing/".length()).split("/")[0];
        sendJson(ex, page(qid, "[[1]]", null));
    }

    private static String page(String id, String dataJson, String nextUri) {
        StringBuilder sb = new StringBuilder("{\"id\":\"").append(id).append('"');
        if (nextUri != null) {
            sb.append(",\"nextUri\":\"").append(nextUri).append('"');
        }
        sb.append(",\"infoUri\":\"http://127.0.0.1:").append(backendPort).append("/ui/query.html?id=").append(id).append('"');
        sb.append(",\"columns\":[{\"name\":\"c1\",\"type\":\"bigint\",")
                .append("\"typeSignature\":{\"rawType\":\"bigint\",\"arguments\":[]}}]");
        if (dataJson != null) {
            sb.append(",\"data\":").append(dataJson);
        }
        sb.append(",\"stats\":{\"state\":\"").append(nextUri != null ? "RUNNING" : "FINISHED").append("\"}");
        return sb.append('}').toString();
    }

    private static void sendJson(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    // ---- HTTP helpers ----

    private static HttpResponse<String> post(String path, String sql, String user) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(gatewayBase + path))
                        .header("X-Auth-User", user)
                        .header("Content-Type", "text/plain")
                        .POST(HttpRequest.BodyPublishers.ofString(sql))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(String path, String jsonBody, String user) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(gatewayBase + path))
                        .header("X-Auth-User", user)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String url, String user) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("X-Auth-User", user)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(String url, String user) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .header("X-Auth-User", user)
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // ---- tests ----

    @Test
    void queryPassesThrough() throws Exception {
        int before = statementHits.get();
        HttpResponse<String> r = post("/v1/statement", "SELECT * FROM t WHERE x = 1", "alice");
        assertEquals(200, r.statusCode());
        JsonNode body = JSON.readTree(r.body());
        assertNull(body.get("error"), "no error expected: " + r.body());
        assertEquals(1, body.get("data").get(0).get(0).asInt());
        assertNull(body.get("nextUri"));
        assertEquals(before + 1, statementHits.get());
    }

    @Test
    void twoPagePollingRewritesNextUri() throws Exception {
        int before = statementHits.get();
        HttpResponse<String> r = post("/v1/statement", "SELECT * FROM t WHERE x = 1 /* SLOW */", "alice");
        assertEquals(200, r.statusCode());
        JsonNode first = JSON.readTree(r.body());
        assertNull(first.get("error"));
        assertNotNull(first.get("nextUri"), "first page must expose nextUri");
        String next = first.get("nextUri").asText();
        assertTrue(next.startsWith(gatewayBase + "/v1/statement/"), "nextUri must point at the gateway: " + next);
        assertEquals(before + 1, statementHits.get(), "only the initial POST reaches the backend");

        HttpResponse<String> r2 = get(next, "alice");
        assertEquals(200, r2.statusCode());
        JsonNode second = JSON.readTree(r2.body());
        assertEquals(1, second.get("data").get(0).get(0).asInt());
        assertNull(second.get("nextUri"), "final page must drop nextUri");
    }

    @Test
    void selectWithoutWhereOrLimitIsRejected() throws Exception {
        int before = statementHits.get();
        HttpResponse<String> r = post("/v1/statement", "SELECT * FROM t", "alice");
        assertEquals(200, r.statusCode());
        JsonNode error = JSON.readTree(r.body()).get("error");
        assertNotNull(error, "validation rejection must carry an error object");
        assertTrue(error.get("message").asText().contains("WHERE 或 LIMIT"), error.toString());
        assertEquals(before, statementHits.get(), "rejected query must not reach the backend");
    }

    @Test
    void selectWithoutFromIsExempt() throws Exception {
        HttpResponse<String> r = post("/v1/statement", "SELECT 1", "alice");
        assertEquals(200, r.statusCode());
        assertNull(JSON.readTree(r.body()).get("error"));
    }

    @Test
    void metadataStatementsAreAllowed() throws Exception {
        HttpResponse<String> r = post("/v1/statement", "SHOW CATALOGS", "alice");
        assertEquals(200, r.statusCode());
        assertNull(JSON.readTree(r.body()).get("error"));
    }

    @Test
    void rateLimitReturns429WithRetryAfter() throws Exception {
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> ok = post("/v1/statement", "SELECT * FROM t WHERE x = " + i, "limited");
            assertEquals(200, ok.statusCode(), "query " + i + " should pass within quota");
        }
        HttpResponse<String> over = post("/v1/statement", "SELECT * FROM t WHERE x = 99", "limited");
        assertEquals(429, over.statusCode());
        assertNotNull(over.headers().firstValue("Retry-After").orElse(null), "429 must carry Retry-After");
    }

    @Test
    void adminDynamicRuleRejectsThenAllows() throws Exception {
        String definition = """
                {"id":"block-pii","name":"block pii","action":"REJECT","version":1,
                 "appliesTo":["QUERY"],
                 "condition":{"forbiddenTables":["ods.pii.user_profile"]},
                 "message":"表 %s 受保护，禁止访问"}
                """;
        HttpResponse<String> added = postJson("/admin/rules", definition, "admin");
        assertEquals(201, added.statusCode());

        HttpResponse<String> blocked = post("/v1/statement", "SELECT * FROM ods.pii.user_profile WHERE x = 1", "alice");
        assertEquals(200, blocked.statusCode());
        assertTrue(JSON.readTree(blocked.body()).get("error").get("message").asText().contains("受保护"));

        HttpResponse<String> removed = delete(gatewayBase + "/admin/rules?id=block-pii", "admin");
        assertEquals(204, removed.statusCode());

        HttpResponse<String> allowed = post("/v1/statement", "SELECT * FROM ods.pii.user_profile WHERE x = 1", "alice");
        assertEquals(200, allowed.statusCode());
        assertNull(JSON.readTree(allowed.body()).get("error"));
    }

    @Test
    void cancelQueryRemovesMapping() throws Exception {
        HttpResponse<String> r = post("/v1/statement", "SELECT * FROM t WHERE x = 1 /* SLOW */", "alice");
        String next = JSON.readTree(r.body()).get("nextUri").asText();

        HttpResponse<String> cancelled = delete(next, "alice");
        assertEquals(204, cancelled.statusCode());

        HttpResponse<String> gone = get(next, "alice");
        assertEquals(404, gone.statusCode());
    }

    @Test
    void infoAndMetricsEndpointsWork() throws Exception {
        HttpResponse<String> info = get(gatewayBase + "/v1/info", "alice");
        assertEquals(200, info.statusCode());
        assertTrue(info.body().contains("trino-446"), info.body());

        HttpResponse<String> metrics = get(gatewayBase + "/metrics", "alice");
        assertEquals(200, metrics.statusCode());
        assertTrue(metrics.body().contains("gateway_queries_total"), metrics.body());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
