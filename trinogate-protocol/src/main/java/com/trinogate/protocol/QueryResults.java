package com.trinogate.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gateway-side representation of the Trino {@code QueryResults} JSON document
 * (see https://trino.io/docs/current/develop/client-protocol.html).
 * Serialized to clients with Jackson; {@code null} fields are omitted so that
 * absent {@code nextUri} marks query completion, exactly like the real coordinator.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryResults {

    public String id;
    public String infoUri;
    public String nextUri;
    public List<Column> columns;
    public List<List<Object>> data;
    public String updateType;
    public QueryError error;
    public List<Object> warnings;
    public StatementStats stats;

    public static QueryResults create() {
        QueryResults r = new QueryResults();
        r.warnings = new ArrayList<>();
        return r;
    }

    public static QueryResults of(Map<String, Object> fields) {
        QueryResults r = create();
        r.id = (String) fields.get("id");
        r.infoUri = (String) fields.get("infoUri");
        r.nextUri = (String) fields.get("nextUri");
        @SuppressWarnings("unchecked")
        List<Column> cols = (List<Column>) fields.get("columns");
        r.columns = cols;
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) fields.get("data");
        r.data = rows;
        r.updateType = (String) fields.get("updateType");
        r.error = (QueryError) fields.get("error");
        r.stats = (StatementStats) fields.get("stats");
        return r;
    }
}
