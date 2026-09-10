package com.trinogate.protocol;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-query context extracted from the initial {@code POST /v1/statement} request.
 * The client only sends {@code X-Trino-*} headers on the first request; the gateway
 * must remember this context for the lifetime of the query (nextUri polling).
 */
public class QueryContext {

    private final String user;
    private final String originalUser;
    private final String source;
    private final String catalog;
    private final String schema;
    private final String timeZone;
    private final String language;
    private final String traceToken;
    private final String transactionId;
    private final Map<String, String> sessionProperties;
    private final Map<String, String> preparedStatements;
    private final Set<String> clientTags;
    private final String clientInfo;
    private final Set<String> clientCapabilities;
    private final Map<String, String> resourceEstimates;
    private final String routingGroup;

    public QueryContext(Builder b) {
        this.user = b.user;
        this.originalUser = b.originalUser;
        this.source = b.source;
        this.catalog = b.catalog;
        this.schema = b.schema;
        this.timeZone = b.timeZone;
        this.language = b.language;
        this.traceToken = b.traceToken;
        this.transactionId = b.transactionId;
        this.sessionProperties = b.sessionProperties;
        this.preparedStatements = b.preparedStatements;
        this.clientTags = b.clientTags;
        this.clientInfo = b.clientInfo;
        this.clientCapabilities = b.clientCapabilities;
        this.resourceEstimates = b.resourceEstimates;
        this.routingGroup = b.routingGroup;
    }

    public static Builder builder(String user) {
        return new Builder(user);
    }

    public String getUser() { return user; }
    public String getOriginalUser() { return originalUser; }
    public String getSource() { return source; }
    public String getCatalog() { return catalog; }
    public String getSchema() { return schema; }
    public String getTimeZone() { return timeZone; }
    public String getLanguage() { return language; }
    public String getTraceToken() { return traceToken; }
    public String getTransactionId() { return transactionId; }
    public Map<String, String> getSessionProperties() { return sessionProperties; }
    public Map<String, String> getPreparedStatements() { return preparedStatements; }
    public Set<String> getClientTags() { return clientTags; }
    public String getClientInfo() { return clientInfo; }
    public Set<String> getClientCapabilities() { return clientCapabilities; }
    public Map<String, String> getResourceEstimates() { return resourceEstimates; }
    public String getRoutingGroup() { return routingGroup; }

    public static final class Builder {
        private final String user;
        private String originalUser;
        private String source;
        private String catalog;
        private String schema;
        private String timeZone;
        private String language;
        private String traceToken;
        private String transactionId;
        private Map<String, String> sessionProperties = new LinkedHashMap<>();
        private Map<String, String> preparedStatements = new LinkedHashMap<>();
        private Set<String> clientTags = new LinkedHashSet<>();
        private String clientInfo;
        private Set<String> clientCapabilities = new LinkedHashSet<>();
        private Map<String, String> resourceEstimates = new LinkedHashMap<>();
        private String routingGroup;

        private Builder(String user) { this.user = user; }

        public Builder originalUser(String v) { this.originalUser = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder catalog(String v) { this.catalog = v; return this; }
        public Builder schema(String v) { this.schema = v; return this; }
        public Builder timeZone(String v) { this.timeZone = v; return this; }
        public Builder language(String v) { this.language = v; return this; }
        public Builder traceToken(String v) { this.traceToken = v; return this; }
        public Builder transactionId(String v) { this.transactionId = v; return this; }
        public Builder sessionProperties(Map<String, String> v) { if (v != null) this.sessionProperties = v; return this; }
        public Builder preparedStatements(Map<String, String> v) { if (v != null) this.preparedStatements = v; return this; }
        public Builder clientTags(Set<String> v) { if (v != null) this.clientTags = v; return this; }
        public Builder clientInfo(String v) { this.clientInfo = v; return this; }
        public Builder clientCapabilities(Set<String> v) { if (v != null) this.clientCapabilities = v; return this; }
        public Builder resourceEstimates(Map<String, String> v) { if (v != null) this.resourceEstimates = v; return this; }
        public Builder routingGroup(String v) { this.routingGroup = v; return this; }

        public QueryContext build() { return new QueryContext(this); }
    }
}
