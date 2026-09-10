package com.trinogate.server;

import com.trinogate.protocol.QueryResults;

import java.util.Map;

/**
 * Result of the query pipeline, mapped by servlets onto HTTP responses:
 * <ul>
 *   <li>OK / VALIDATION_REJECTED / FORWARD_ERROR → 200 with QueryResults</li>
 *   <li>RATE_LIMITED / QUEUE_FULL / QUEUE_TIMEOUT → 429 + Retry-After</li>
 *   <li>NO_BACKEND → 503</li>
 *   <li>NOT_FOUND → 404</li>
 * </ul>
 */
public record PipelineResult(
        Status status,
        int httpStatus,
        Integer retryAfterSeconds,
        QueryResults results,
        Map<String, String> responseHeaders) {

    public enum Status {
        OK,
        VALIDATION_REJECTED,
        RATE_LIMITED,
        QUEUE_FULL,
        QUEUE_TIMEOUT,
        NO_BACKEND,
        FORWARD_ERROR,
        NOT_FOUND
    }

    public static PipelineResult ok(QueryResults results, Map<String, String> headers) {
        return new PipelineResult(Status.OK, 200, null, results, headers);
    }

    public static PipelineResult validationRejected(QueryResults results) {
        return new PipelineResult(Status.VALIDATION_REJECTED, 200, null, results, Map.of());
    }

    public static PipelineResult rejected(int httpStatus, int retryAfterSeconds, QueryResults results) {
        return new PipelineResult(Status.RATE_LIMITED, httpStatus, retryAfterSeconds, results, Map.of());
    }

    public static PipelineResult noBackend() {
        return new PipelineResult(Status.NO_BACKEND, 503, null, null, Map.of());
    }

    public static PipelineResult forwardError(QueryResults results) {
        return new PipelineResult(Status.FORWARD_ERROR, 200, null, results, Map.of());
    }

    public static PipelineResult notFound() {
        return new PipelineResult(Status.NOT_FOUND, 404, null, null, Map.of());
    }
}
