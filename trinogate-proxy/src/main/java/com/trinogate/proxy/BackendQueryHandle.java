package com.trinogate.proxy;

import com.trinogate.protocol.Column;
import com.trinogate.protocol.QueryError;
import com.trinogate.protocol.StatementStats;
import io.trino.client.ClientTypeSignature;
import io.trino.client.ErrorLocation;
import io.trino.client.QueryStatusInfo;
import io.trino.client.StatementClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Wraps a {@link StatementClient} (the gateway's connection to one backend query).
 * The pull protocol is mapped 1:1: the initial POST response is page 1; every
 * client GET on the gateway triggers one {@link StatementClient#advance()}.
 */
public class BackendQueryHandle implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackendQueryHandle.class);

    private final StatementClient client;
    private final String backendQueryId;

    public BackendQueryHandle(StatementClient client) {
        this.client = client;
        this.backendQueryId = client.currentStatusInfo().getId();
    }

    /** Page 1: the response of the initial POST. */
    public synchronized BackendPage firstPage() {
        return buildPage();
    }

    /** Fetch the next page from the backend (blocks up to the read timeout). */
    public synchronized BackendPage fetchNext() {
        if (client.isClientAborted() || client.isClientError()) {
            return buildPage(); // terminal state, no more pages
        }
        try {
            client.advance();
        } catch (RuntimeException e) {
            log.warn("Backend advance failed for query {}: {}", backendQueryId, e.toString());
            return buildPage();
        }
        return buildPage();
    }

    public boolean isFinished() {
        return client.isFinished();
    }

    public String getBackendQueryId() {
        return backendQueryId;
    }

    /** Cancels the backend query (StatementClient.close aborts). */
    @Override
    public synchronized void close() {
        try {
            client.close();
        } catch (Exception e) {
            log.warn("Failed to close backend query {}: {}", backendQueryId, e.toString());
        }
    }

    private BackendPage buildPage() {
        QueryStatusInfo status = client.currentStatusInfo();
        List<Column> columns = null;
        if (status.getColumns() != null) {
            columns = new ArrayList<>(status.getColumns().size());
            for (io.trino.client.Column c : status.getColumns()) {
                ClientTypeSignature sig = c.getTypeSignature();
                columns.add(new Column(c.getName(), c.getType(), sig));
            }
        }
        List<List<Object>> data = null;
        if (client.currentData() != null && client.currentData().getData() != null) {
            data = new ArrayList<>();
            for (List<Object> row : client.currentData().getData()) {
                data.add(row);
            }
        }
        QueryError error = mapError(status.getError());
        StatementStats stats = mapStats(status);
        String nextUri = status.getNextUri() == null ? null : status.getNextUri().toString();

        return new BackendPage(
                status.getId(),
                status.getInfoUri() == null ? null : status.getInfoUri().toString(),
                nextUri,
                columns,
                data,
                status.getUpdateType(),
                error,
                stats,
                client.getSetCatalog().orElse(null),
                client.getSetSchema().orElse(null),
                client.getSetSessionProperties(),
                client.getResetSessionProperties(),
                client.getStartedTransactionId() == null || client.getStartedTransactionId().isEmpty()
                        ? null : client.getStartedTransactionId(),
                client.isClearTransactionId(),
                client.getAddedPreparedStatements(),
                client.getDeallocatedPreparedStatements());
    }

    private static QueryError mapError(io.trino.client.QueryError e) {
        if (e == null) {
            return null;
        }
        Map<String, Object> location = null;
        ErrorLocation el = e.getErrorLocation();
        if (el != null) {
            location = Map.of("lineNumber", el.getLineNumber(), "columnNumber", el.getColumnNumber());
        }
        QueryError err = QueryError.of(
                e.getMessage() == null ? "Trino query failed" : e.getMessage(),
                e.getSqlState(),
                e.getErrorCode(),
                e.getErrorName(),
                e.getErrorType());
        err.errorLocation = location;
        return err;
    }

    private static StatementStats mapStats(QueryStatusInfo status) {
        io.trino.client.StatementStats s = status.getStats();
        if (s == null) {
            return null;
        }
        StatementStats out = StatementStats.of(s.getState());
        out.queuedTimeMillis = s.getQueuedTimeMillis();
        out.elapsedTimeMillis = s.getElapsedTimeMillis();
        out.cpuTimeMillis = s.getCpuTimeMillis();
        out.wallTimeMillis = s.getWallTimeMillis();
        out.processedRows = s.getProcessedRows();
        out.processedBytes = s.getProcessedBytes();
        out.physicalInputBytes = s.getPhysicalInputBytes();
        return out;
    }

    static Map<String, Object> errorToMap(QueryError e) {
        if (e == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", e.message);
        m.put("sqlState", e.sqlState);
        m.put("errorCode", e.errorCode);
        m.put("errorName", e.errorName);
        m.put("errorType", e.errorType);
        return m;
    }
}
