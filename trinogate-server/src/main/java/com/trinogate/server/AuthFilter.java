package com.trinogate.server;

import com.trinogate.auth.Authenticator;
import com.trinogate.auth.IdentityContext;
import com.trinogate.protocol.ProtocolHeaders;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates every request, then exposes the identity and trace token as
 * request attributes consumed by the pipeline servlets.
 */
public class AuthFilter implements Filter {

    public static final String IDENTITY_ATTRIBUTE = "gateway.identity";
    public static final String TRACE_ATTRIBUTE = "gateway.traceToken";

    private final Authenticator authenticator;

    public AuthFilter(Authenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = httpRequest.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, httpRequest.getHeader(name));
        }

        Optional<IdentityContext> identity = authenticator.authenticate(headers, httpRequest.getRemoteAddr());
        if (identity.isEmpty()) {
            httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            httpResponse.setHeader("WWW-Authenticate", "Basic realm=\"trino-gateway\"");
            return;
        }
        httpRequest.setAttribute(IDENTITY_ATTRIBUTE, identity.get());
        String trace = httpRequest.getHeader(ProtocolHeaders.TRACE_TOKEN);
        if (trace != null) {
            httpRequest.setAttribute(TRACE_ATTRIBUTE, trace);
        }
        chain.doFilter(request, response);
    }
}
