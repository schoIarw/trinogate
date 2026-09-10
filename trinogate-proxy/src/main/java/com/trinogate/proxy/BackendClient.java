package com.trinogate.proxy;

import com.trinogate.config.ProxyConfig;
import com.trinogate.protocol.QueryContext;
import io.trino.client.ClientSession;
import io.trino.client.StatementClient;
import io.trino.client.StatementClientFactory;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Creates backend {@link StatementClient} instances from gateway query contexts
 * (M6 forwarding). Identity propagation is the "impersonation" model: the gateway
 * connects with the authenticated user carried in {@code X-Trino-User}.
 */
public class BackendClient {

    private final OkHttpClient http;
    private final ProxyConfig config;

    public BackendClient(ProxyConfig config) {
        this.config = config;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(config.getConnectTimeoutMs()))
                .readTimeout(Duration.ofMillis(config.getReadTimeoutMs()))
                .build();
    }

    public BackendQueryHandle submit(BackendCluster cluster, QueryContext ctx, String sql) {
        ClientSession session = toClientSession(cluster, ctx);
        StatementClient client = StatementClientFactory.newStatementClient(http, session, sql);
        return new BackendQueryHandle(client);
    }

    public OkHttpClient http() {
        return http;
    }

    private ClientSession toClientSession(BackendCluster cluster, QueryContext ctx) {
        ClientSession.Builder b = ClientSession.builder()
                .server(cluster.getUrl())
                .user(Optional.ofNullable(ctx.getUser()))
                .source(ctx.getSource())
                .catalog(ctx.getCatalog())
                .schema(ctx.getSchema())
                .timeZone(timeZoneOf(ctx.getTimeZone()))
                .locale(Locale.ENGLISH)
                .transactionId(ctx.getTransactionId())
                .properties(ctx.getSessionProperties())
                .preparedStatements(ctx.getPreparedStatements())
                .clientTags(ctx.getClientTags() == null ? Set.of() : ctx.getClientTags())
                .clientInfo(ctx.getClientInfo())
                .traceToken(Optional.ofNullable(ctx.getTraceToken()))
                .resourceEstimates(ctx.getResourceEstimates() == null ? java.util.Map.of() : ctx.getResourceEstimates());
        return b.build();
    }

    private static ZoneId timeZoneOf(String value) {
        if (value == null || value.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(value, ZoneId.SHORT_IDS);
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }
}
