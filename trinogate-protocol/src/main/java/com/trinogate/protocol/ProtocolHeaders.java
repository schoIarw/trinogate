package com.trinogate.protocol;

/** Constants for Trino client protocol HTTP headers (request and response side). */
public final class ProtocolHeaders {

    private ProtocolHeaders() {}

    // request headers
    public static final String USER = "X-Trino-User";
    public static final String ORIGINAL_USER = "X-Trino-Original-User";
    public static final String SOURCE = "X-Trino-Source";
    public static final String CATALOG = "X-Trino-Catalog";
    public static final String SCHEMA = "X-Trino-Schema";
    public static final String TIME_ZONE = "X-Trino-Time-Zone";
    public static final String LANGUAGE = "X-Trino-Language";
    public static final String TRACE_TOKEN = "X-Trino-Trace-Token";
    public static final String SESSION = "X-Trino-Session";
    public static final String ROLE = "X-Trino-Role";
    public static final String PREPARED_STATEMENT = "X-Trino-Prepared-Statement";
    public static final String TRANSACTION_ID = "X-Trino-Transaction-Id";
    public static final String CLIENT_INFO = "X-Trino-Client-Info";
    public static final String CLIENT_TAGS = "X-Trino-Client-Tags";
    public static final String CLIENT_CAPABILITIES = "X-Trino-Client-Capabilities";
    public static final String RESOURCE_ESTIMATE = "X-Trino-Resource-Estimate";
    public static final String EXTRA_CREDENTIAL = "X-Trino-Extra-Credential";

    // response headers (session state written back to the client, cookie-like)
    public static final String SET_CATALOG = "X-Trino-Set-Catalog";
    public static final String SET_SCHEMA = "X-Trino-Set-Schema";
    public static final String SET_AUTHORIZATION_USER = "X-Trino-Set-Authorization-User";
    public static final String RESET_AUTHORIZATION_USER = "X-Trino-Reset-Authorization-User";
    public static final String SET_ORIGINAL_ROLES = "X-Trino-Set-Original-Roles";
    public static final String SET_SESSION = "X-Trino-Set-Session";
    public static final String CLEAR_SESSION = "X-Trino-Clear-Session";
    public static final String SET_ROLE = "X-Trino-Set-Role";
    public static final String ADDED_PREPARE = "X-Trino-Added-Prepare";
    public static final String DEALLOCATED_PREPARE = "X-Trino-Deallocated-Prepare";
    public static final String STARTED_TRANSACTION_ID = "X-Trino-Started-Transaction-Id";
    public static final String CLEAR_TRANSACTION_ID = "X-Trino-Clear-Transaction-Id";
}
