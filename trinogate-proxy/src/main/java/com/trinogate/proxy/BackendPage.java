package com.trinogate.proxy;

import com.trinogate.protocol.Column;
import com.trinogate.protocol.QueryError;
import com.trinogate.protocol.StatementStats;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One page of results read from the backend, already mapped to gateway protocol
 * POJOs, plus session-state changes the gateway must echo to the client via
 * {@code X-Trino-Set-*} response headers.
 */
public class BackendPage {

    private final String id;
    private final String infoUri;
    /** Backend nextUri; the gateway rewrites it before returning to the client. */
    private final String nextUri;
    private final List<Column> columns;
    private final List<List<Object>> data;
    private final String updateType;
    private final QueryError error;
    private final StatementStats stats;

    // session state changes to propagate
    private final String setCatalog;
    private final String setSchema;
    private final Map<String, String> setSessionProperties;
    private final Set<String> resetSessionProperties;
    private final String startedTransactionId;
    private final boolean clearTransactionId;
    private final Map<String, String> addedPreparedStatements;
    private final Set<String> deallocatedPreparedStatements;

    public BackendPage(String id, String infoUri, String nextUri, List<Column> columns,
                       List<List<Object>> data, String updateType, QueryError error, StatementStats stats,
                       String setCatalog, String setSchema, Map<String, String> setSessionProperties,
                       Set<String> resetSessionProperties, String startedTransactionId, boolean clearTransactionId,
                       Map<String, String> addedPreparedStatements, Set<String> deallocatedPreparedStatements) {
        this.id = id;
        this.infoUri = infoUri;
        this.nextUri = nextUri;
        this.columns = columns;
        this.data = data;
        this.updateType = updateType;
        this.error = error;
        this.stats = stats;
        this.setCatalog = setCatalog;
        this.setSchema = setSchema;
        this.setSessionProperties = setSessionProperties;
        this.resetSessionProperties = resetSessionProperties;
        this.startedTransactionId = startedTransactionId;
        this.clearTransactionId = clearTransactionId;
        this.addedPreparedStatements = addedPreparedStatements;
        this.deallocatedPreparedStatements = deallocatedPreparedStatements;
    }

    public String getId() { return id; }
    public String getInfoUri() { return infoUri; }
    public String getNextUri() { return nextUri; }
    public List<Column> getColumns() { return columns; }
    public List<List<Object>> getData() { return data; }
    public String getUpdateType() { return updateType; }
    public QueryError getError() { return error; }
    public StatementStats getStats() { return stats; }
    public String getSetCatalog() { return setCatalog; }
    public String getSetSchema() { return setSchema; }
    public Map<String, String> getSetSessionProperties() { return setSessionProperties; }
    public Set<String> getResetSessionProperties() { return resetSessionProperties; }
    public String getStartedTransactionId() { return startedTransactionId; }
    public boolean isClearTransactionId() { return clearTransactionId; }
    public Map<String, String> getAddedPreparedStatements() { return addedPreparedStatements; }
    public Set<String> getDeallocatedPreparedStatements() { return deallocatedPreparedStatements; }
}
