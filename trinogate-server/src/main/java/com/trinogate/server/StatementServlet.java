package com.trinogate.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinogate.auth.IdentityContext;
import com.trinogate.protocol.NextUriRewriter;
import com.trinogate.protocol.QueryContext;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Trino statement endpoint (design doc M1):
 * <ul>
 *   <li>POST /v1/statement — submit SQL (body) with X-Trino-* headers</li>
 *   <li>GET  /v1/statement/{gatewayId}/{token} — poll next page (rewritten nextUri)</li>
 *   <li>DELETE /v1/statement/{gatewayId}/{token} — cancel the query</li>
 * </ul>
 */
public class StatementServlet extends HttpServlet {

    private static final int MAX_SQL_BYTES = 1024 * 1024;

    private final QueryPipeline pipeline;
    private final ObjectMapper mapper = new ObjectMapper();

    public StatementServlet(QueryPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        IdentityContext identity = (IdentityContext) req.getAttribute(AuthFilter.IDENTITY_ATTRIBUTE);
        String sql = readBody(req);
        if (sql == null) {
            resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "SQL exceeds size limit");
            return;
        }
        QueryContext ctx = QueryContextParser.parse(req, identity.getUser());
        PipelineResult result = pipeline.submit(sql, identity, ctx);
        writeResult(resp, result);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = NextUriRewriter.parse(req.getRequestURI());
        if (parts == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "unknown nextUri");
            return;
        }
        String gatewayId = parts[0];
        long token = parseToken(parts[1]);
        if (token < 0) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "invalid token");
            return;
        }
        writeResult(resp, pipeline.poll(gatewayId, token));
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String[] parts = NextUriRewriter.parse(req.getRequestURI());
        if (parts == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "unknown nextUri");
            return;
        }
        PipelineResult result = pipeline.cancel(parts[0]);
        if (result.httpStatus() == 204) {
            resp.setStatus(204);
            return;
        }
        resp.sendError(result.httpStatus(), "unknown query");
    }

    private void writeResult(HttpServletResponse resp, PipelineResult result) throws IOException {
        if (result.responseHeaders() != null) {
            result.responseHeaders().forEach(resp::setHeader);
        }
        if (result.httpStatus() != 200) {
            resp.setStatus(result.httpStatus());
            if (result.retryAfterSeconds() != null) {
                resp.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            }
            if (result.results() != null) {
                mapper.writeValue(resp.getWriter(), result.results());
            }
            return;
        }
        resp.setStatus(200);
        resp.setContentType("application/json");
        mapper.writeValue(resp.getWriter(), result.results());
    }

    private static String readBody(HttpServletRequest req) throws IOException {
        int length = req.getContentLength();
        if (length > MAX_SQL_BYTES) {
            return null;
        }
        try (InputStream in = req.getInputStream()) {
            byte[] buffer = in.readAllBytes();
            if (buffer.length > MAX_SQL_BYTES) {
                return null;
            }
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    private static long parseToken(String token) {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
