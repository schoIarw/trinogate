package com.trinogate.validation.sql;

import io.trino.sql.parser.ParsingException;
import io.trino.sql.parser.SqlParser;
import io.trino.sql.tree.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Thin wrapper over the official Trino parser ({@code io.trino:trino-parser}).
 * The parser version must stay aligned with the backend Trino version range
 * supported by the gateway.
 */
public class SqlParserService {

    private static final Logger log = LoggerFactory.getLogger(SqlParserService.class);

    private final SqlParser parser = new SqlParser();

    public Optional<Statement> parse(String sql) {
        String trimmed = sql.trim();
        while (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parser.createStatement(trimmed));
        } catch (ParsingException e) {
            log.debug("SQL parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
