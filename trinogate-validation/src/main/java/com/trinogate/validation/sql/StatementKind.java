package com.trinogate.validation.sql;

/** Broad statement categories used by validation rules. */
public enum StatementKind {
    QUERY,
    INSERT,
    UPDATE,
    DELETE,
    MERGE,
    CALL,
    PREPARE,
    EXECUTE,
    DEALLOCATE,
    METADATA,   // SHOW / DESCRIBE / USE / SET SESSION / EXPLAIN ...
    DDL,        // CREATE / DROP / ALTER / GRANT / REVOKE ...
    OTHER;

    public static StatementKind parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
