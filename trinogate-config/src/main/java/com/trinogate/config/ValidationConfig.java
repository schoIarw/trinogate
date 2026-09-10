package com.trinogate.config;

/** SQL validation and rule engine settings (design doc M3). */
public class ValidationConfig {

    /** Default action when a rule matches: REJECT or WARN. */
    private String policy = "REJECT";

    /** Directory scanned for YAML rule definition files (hot reload). */
    private String rulesPath = "rules";

    /** Polling interval (seconds) for rule file hot reload. */
    private int rulesPollSeconds = 5;

    /** Built-in: SELECT statements must contain WHERE or LIMIT. */
    private boolean selectRequireWhereOrLimit = true;

    /** Exempt SELECT without FROM (e.g. SELECT 1). */
    private boolean exemptNoFrom = true;

    /** Recursively validate subqueries (CTEs / table subqueries). */
    private boolean includeSubqueries = false;

    /** Metadata statements (SHOW/DESCRIBE/SET/USE/EXPLAIN...) are always allowed. */
    private boolean allowMetadataStatements = true;

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }
    public String getRulesPath() { return rulesPath; }
    public void setRulesPath(String rulesPath) { this.rulesPath = rulesPath; }
    public int getRulesPollSeconds() { return rulesPollSeconds; }
    public void setRulesPollSeconds(int rulesPollSeconds) { this.rulesPollSeconds = rulesPollSeconds; }
    public boolean isSelectRequireWhereOrLimit() { return selectRequireWhereOrLimit; }
    public void setSelectRequireWhereOrLimit(boolean selectRequireWhereOrLimit) { this.selectRequireWhereOrLimit = selectRequireWhereOrLimit; }
    public boolean isExemptNoFrom() { return exemptNoFrom; }
    public void setExemptNoFrom(boolean exemptNoFrom) { this.exemptNoFrom = exemptNoFrom; }
    public boolean isIncludeSubqueries() { return includeSubqueries; }
    public void setIncludeSubqueries(boolean includeSubqueries) { this.includeSubqueries = includeSubqueries; }
    public boolean isAllowMetadataStatements() { return allowMetadataStatements; }
    public void setAllowMetadataStatements(boolean allowMetadataStatements) { this.allowMetadataStatements = allowMetadataStatements; }
}
