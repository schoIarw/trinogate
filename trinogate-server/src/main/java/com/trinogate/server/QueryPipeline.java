package com.trinogate.server;

import com.trinogate.auth.IdentityContext;
import com.trinogate.flow.Admission;
import com.trinogate.flow.FlowController;
import com.trinogate.protocol.Column;
import com.trinogate.protocol.NextUriRewriter;
import com.trinogate.protocol.ProtocolHeaders;
import com.trinogate.protocol.QueryContext;
import com.trinogate.protocol.QueryError;
import com.trinogate.protocol.QueryRegistry;
import com.trinogate.protocol.QueryResults;
import com.trinogate.protocol.RegisteredQuery;
import com.trinogate.proxy.BackendClient;
import com.trinogate.proxy.BackendCluster;
import com.trinogate.proxy.BackendPage;
import com.trinogate.proxy.BackendQueryHandle;
import com.trinogate.proxy.ClusterRegistry;
import com.trinogate.validation.SqlValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrates a query end to end (design doc section 5.1):
 * validation → admission (rate limit/queue/concurrency) → route → forward →
 * register → rewrite nextUri → build the first QueryResults.
 */
public class QueryPipeline {

    private static final Logger log = LoggerFactory.getLogger(QueryPipeline.class);

    private final SqlValidationService validation;
    private final FlowController flow;
    private final ClusterRegistry clusters;
    private final BackendClient backend;
    private final QueryRegistry<BackendQueryHandle> registry;
    private final String gatewayBaseUri;
    private final GatewayMetrics metrics;

    public QueryPipeline(SqlValidationService validation, FlowController flow, ClusterRegistry clusters,
                         BackendClient backend, QueryRegistry<BackendQueryHandle> registry,
                         String gatewayBaseUri, GatewayMetrics metrics) {
        this.validation = validation;
        this.flow = flow;
        this.clusters = clusters;
        this.backend = backend;
        this.registry = registry;
        this.gatewayBaseUri = gatewayBaseUri;
        this.metrics = metrics;
    }

    public PipelineResult submit(String sql, IdentityContext identity, QueryContext ctx) {
        metrics.submitted();
        log.debug("submit user={} source={} sql={}", identity.getUser(), ctx.getSource(), sql);

        Optional<String> rejection = validation.validate(sql, identity.getUser(), ctx.getSource());
        if (rejection.isPresent()) {
            metrics.validationRejected();
            QueryResults results = errorResults("2026" + System.currentTimeMillis() % 1_000_000,
                    QueryError.userError(rejection.get()));
            return PipelineResult.validationRejected(results);
        }

        Admission admission = flow.admit(identity.getUser());
        if (!admission.isAllowed()) {
            metrics.flowRejected();
            QueryResults results = errorResults("2026" + System.currentTimeMillis() % 1_000_000,
                    QueryError.userError("网关流量限制: " + admission));
            return PipelineResult.rejected(429, admission.getRetryAfterSeconds(), results);
        }
        try (var permit = admission.getPermit()) {
            Optional<BackendCluster> cluster = clusters.pick();
            if (cluster.isEmpty()) {
                return PipelineResult.noBackend();
            }
            try {
                BackendQueryHandle handle = backend.submit(cluster.get(), ctx, sql);
                String gatewayId = UUID.randomUUID().toString().replace("-", "");
                RegisteredQuery<BackendQueryHandle> rq = new RegisteredQuery<>(
                        gatewayId,
                        handle.getBackendQueryId(),
                        identity.getUser(),
                        ctx.getSource(),
                        cluster.get().getName(),
                        handle,
                        handle::close);
                if (!registry.register(gatewayId, rq)) {
                    handle.close();
                    return PipelineResult.forwardError(errorResults("2026" + System.currentTimeMillis() % 1_000_000,
                            QueryError.gatewayError("gateway id collision, retry")));
                }
                BackendPage page = handle.firstPage();
                metrics.forwarded();
                return PipelineResult.ok(toResults(page, rq), pageHeaders(page));
            } catch (Exception e) {
                metrics.error();
                log.error("Forward to {} failed: {}", cluster.get().getName(), e.toString());
                return PipelineResult.forwardError(errorResults("2026" + System.currentTimeMillis() % 1_000_000,
                        QueryError.gatewayError("网关转发失败: " + e.getMessage())));
            }
        }
    }

    public PipelineResult poll(String gatewayId, long token) {
        Optional<RegisteredQuery<BackendQueryHandle>> registered = registry.get(gatewayId);
        if (registered.isEmpty()) {
            return PipelineResult.notFound();
        }
        RegisteredQuery<BackendQueryHandle> rq = registered.get();
        BackendQueryHandle handle = rq.getHandle();
        BackendPage page = handle.fetchNext();
        if (page.getNextUri() == null) {
            registry.remove(gatewayId); // finished: drop mapping, backend client closes on GC-free path
        }
        return PipelineResult.ok(toResults(page, rq), pageHeaders(page));
    }

    public PipelineResult cancel(String gatewayId) {
        Optional<RegisteredQuery<BackendQueryHandle>> registered = registry.remove(gatewayId);
        if (registered.isEmpty()) {
            return PipelineResult.notFound();
        }
        metrics.cancelled();
        registered.get().getHandle().close();
        log.info("Cancelled gateway query {}", gatewayId);
        return new PipelineResult(PipelineResult.Status.OK, 204, null, null, Map.of());
    }

    public List<Map<String, Object>> activeQueries() {
        return registry.all().stream().map(q -> Map.<String, Object>of(
                "gatewayId", q.getGatewayId(),
                "backendQueryId", q.getBackendQueryId(),
                "user", q.getUser(),
                "source", q.getSource() == null ? "" : q.getSource(),
                "cluster", q.getCluster(),
                "createdAtMillis", q.getCreatedAtMillis(),
                "idleMillis", q.idleMillis())).toList();
    }

    private QueryResults toResults(BackendPage page, RegisteredQuery<BackendQueryHandle> rq) {
        QueryResults results = QueryResults.create();
        results.id = page.getId();
        results.infoUri = page.getInfoUri();
        results.columns = page.getColumns();
        results.data = page.getData();
        results.updateType = page.getUpdateType();
        results.error = page.getError();
        results.stats = page.getStats();
        if (page.getNextUri() != null) {
            results.nextUri = NextUriRewriter.build(gatewayBaseUri, rq.getGatewayId(), rq.nextToken());
        }
        return results;
    }

    private static Map<String, String> pageHeaders(BackendPage page) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        put(headers, ProtocolHeaders.SET_CATALOG, page.getSetCatalog());
        put(headers, ProtocolHeaders.SET_SCHEMA, page.getSetSchema());
        put(headers, ProtocolHeaders.STARTED_TRANSACTION_ID, page.getStartedTransactionId());
        if (page.isClearTransactionId()) {
            headers.put(ProtocolHeaders.CLEAR_TRANSACTION_ID, "true");
        }
        page.getSetSessionProperties().forEach((k, v) -> headers.put(ProtocolHeaders.SET_SESSION, k + "=" + v));
        page.getResetSessionProperties().forEach(k -> headers.put(ProtocolHeaders.CLEAR_SESSION, k));
        page.getAddedPreparedStatements().forEach((k, v) -> headers.put(ProtocolHeaders.ADDED_PREPARE, k + "=" + v));
        page.getDeallocatedPreparedStatements().forEach(k -> headers.put(ProtocolHeaders.DEALLOCATED_PREPARE, k));
        return headers;
    }

    private static void put(Map<String, String> headers, String name, String value) {
        if (value != null && !value.isEmpty()) {
            headers.put(name, value);
        }
    }

    private static QueryResults errorResults(String id, QueryError error) {
        QueryResults results = QueryResults.create();
        results.id = id;
        results.error = error;
        return results;
    }
}
