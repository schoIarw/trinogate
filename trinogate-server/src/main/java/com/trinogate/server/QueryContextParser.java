package com.trinogate.server;

import com.trinogate.protocol.ProtocolHeaders;
import com.trinogate.protocol.QueryContext;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link QueryContext} from the initial POST /v1/statement request.
 * The authenticated user always wins over the client-supplied X-Trino-User,
 * preventing identity spoofing through headers.
 */
public final class QueryContextParser {

    private QueryContextParser() {}

    public static QueryContext parse(HttpServletRequest request, String authenticatedUser) {
        String session = header(request, ProtocolHeaders.SESSION);
        String prepared = header(request, ProtocolHeaders.PREPARED_STATEMENT);
        String resourceEstimate = header(request, ProtocolHeaders.RESOURCE_ESTIMATE);

        QueryContext.Builder builder = QueryContext.builder(authenticatedUser)
                .originalUser(header(request, ProtocolHeaders.ORIGINAL_USER))
                .source(header(request, ProtocolHeaders.SOURCE))
                .catalog(header(request, ProtocolHeaders.CATALOG))
                .schema(header(request, ProtocolHeaders.SCHEMA))
                .timeZone(header(request, ProtocolHeaders.TIME_ZONE))
                .language(header(request, ProtocolHeaders.LANGUAGE))
                .traceToken(header(request, ProtocolHeaders.TRACE_TOKEN))
                .transactionId(header(request, ProtocolHeaders.TRANSACTION_ID))
                .clientInfo(header(request, ProtocolHeaders.CLIENT_INFO))
                .sessionProperties(parseKv(session))
                .preparedStatements(parseKv(prepared))
                .resourceEstimates(parseKv(resourceEstimate))
                .clientTags(splitCsv(header(request, ProtocolHeaders.CLIENT_TAGS)))
                .clientCapabilities(splitCsv(header(request, ProtocolHeaders.CLIENT_CAPABILITIES)))
                .routingGroup(header(request, "X-Trino-Routing-Group"));
        return builder.build();
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static Map<String, String> parseKv(String csv) {
        Map<String, String> map = new LinkedHashMap<>();
        if (csv == null || csv.isBlank()) {
            return map;
        }
        for (String part : csv.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int eq = item.indexOf('=');
            if (eq > 0) {
                map.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static Set<String> splitCsv(String csv) {
        Set<String> set = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return set;
        }
        for (String part : csv.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                set.add(item);
            }
        }
        return set;
    }
}
