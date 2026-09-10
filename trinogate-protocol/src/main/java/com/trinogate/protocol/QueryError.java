package com.trinogate.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * {@code QueryError} as understood by Trino clients. A validation rejection is
 * reported as HTTP 200 with this object inside {@link QueryResults#error} so that
 * JDBC clients (e.g. DBeaver) surface a clean SQL error message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryError {

    public String message;
    public String sqlState;
    public Integer errorCode;
    public String errorName;
    public String errorType;
    public Map<String, Object> errorLocation;

    public static QueryError of(String message, String sqlState, int errorCode, String errorName, String errorType) {
        QueryError e = new QueryError();
        e.message = message;
        e.sqlState = sqlState;
        e.errorCode = errorCode;
        e.errorName = errorName;
        e.errorType = errorType;
        return e;
    }

    public static QueryError userError(String message) {
        return of(message, "USER_ERROR", 0x00010000, "USER_ERROR", "USER_ERROR");
    }

    public static QueryError gatewayError(String message) {
        return of(message, "INTERNAL_ERROR", 0x00020000, "INTERNAL_ERROR", "INTERNAL_ERROR");
    }
}
