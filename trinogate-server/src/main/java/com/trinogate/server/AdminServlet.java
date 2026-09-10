package com.trinogate.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinogate.config.FlowConfig;
import com.trinogate.flow.FlowController;
import com.trinogate.proxy.ClusterRegistry;
import com.trinogate.validation.SqlValidationService;
import com.trinogate.validation.rules.RuleDefinition;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

/**
 * Admin API (design doc M8):
 * <ul>
 *   <li>GET/POST/DELETE /admin/rules — list, add, remove dynamic rules (hot reload)</li>
 *   <li>GET /admin/flow?user= — rate/queue/concurrency statistics</li>
 *   <li>GET /admin/clusters — backend health and activity</li>
 *   <li>GET/DELETE /admin/queries — active gateway queries / cancel one</li>
 * </ul>
 */
public class AdminServlet extends HttpServlet {

    private final SqlValidationService validation;
    private final FlowController flow;
    private final ClusterRegistry clusters;
    private final QueryPipeline pipeline;
    private final FlowConfig flowConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    public AdminServlet(SqlValidationService validation, FlowController flow, ClusterRegistry clusters,
                        QueryPipeline pipeline, FlowConfig flowConfig) {
        this.validation = validation;
        this.flow = flow;
        this.clusters = clusters;
        this.pipeline = pipeline;
        this.flowConfig = flowConfig;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        if (path.endsWith("/rules")) {
            json(resp, Map.of("rules", validation.ruleManager().activeRules().stream()
                    .map(r -> Map.of("id", r.id(), "name", r.name(), "version", r.version(),
                            "enabled", r.enabled(), "action", r.action().name(),
                            "appliesTo", r.appliesTo().stream().map(Enum::name).toList()))
                    .toList()));
            return;
        }
        if (path.endsWith("/flow")) {
            json(resp, flow.stats(req.getParameter("user") == null ? "" : req.getParameter("user")));
            return;
        }
        if (path.endsWith("/clusters")) {
            json(resp, clusters.status());
            return;
        }
        if (path.endsWith("/queries")) {
            json(resp, Map.of("queries", pipeline.activeQueries()));
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (req.getRequestURI().endsWith("/rules")) {
            RuleDefinition definition = mapper.readValue(req.getInputStream(), RuleDefinition.class);
            validation.ruleManager().addRule(definition);
            resp.setStatus(201);
            json(resp, Map.of("added", definition.id));
            return;
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String path = req.getRequestURI();
        if (path.endsWith("/rules")) {
            String id = req.getParameter("id");
            boolean removed = id != null && validation.ruleManager().removeRule(id);
            resp.setStatus(removed ? 204 : 404);
            return;
        }
        if (path.endsWith("/queries")) {
            String id = req.getParameter("id");
            if (id != null) {
                PipelineResult result = pipeline.cancel(id);
                resp.setStatus(result.httpStatus());
                return;
            }
        }
        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    private void json(HttpServletResponse resp, Object value) throws IOException {
        resp.setContentType("application/json");
        mapper.writeValue(resp.getWriter(), value);
    }
}
