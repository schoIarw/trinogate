package com.trinogate.validation.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * YAML rule definition model. Example:
 * <pre>
 * rules:
 *   - id: forbid-sensitive-tables
 *     name: 禁止访问敏感表
 *     enabled: true
 *     action: REJECT
 *     version: 1
 *     appliesTo: [QUERY, INSERT, UPDATE, DELETE, MERGE]
 *     exceptUsers: [etl_sa]
 *     condition:
 *       forbiddenTables: ["ods.pii.user_profile"]
 *     message: "表 %s 受保护，禁止访问"
 * </pre>
 */
public class RuleDefinition {

    public String id;
    public String name = "";
    public int version = 1;
    public boolean enabled = true;
    public String action = "REJECT";
    public List<String> appliesTo = new ArrayList<>();
    public List<String> exceptUsers = new ArrayList<>();
    public String message = "";
    public Condition condition = new Condition();

    public static class Condition {
        /** Reject when the query references any of these tables (catalog.schema.table). */
        public List<String> forbiddenTables = new ArrayList<>();
        /** Reject when the statement kind is in this list. */
        public List<String> statementKinds = new ArrayList<>();
        /** Reject when the SQL text contains this value (case-insensitive fallback). */
        public String sqlContains = "";
    }
}
