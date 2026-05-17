package com.simi.console.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that injects trace context into MDC for structured logging.
 * All log lines within a request will have:
 * <ul>
 *   <li>traceId — from X-Trace-Id header, or auto-generated UUID</li>
 *   <li>userId — from X-User-Id header</li>
 *   <li>requestId — from X-Request-Id header</li>
 * </ul>
 * <p>
 * These fields appear in JSON logs (logback-spring.xml LogstashEncoder)
 * and enable CloudWatch Logs Insights queries like:
 * {@code fields @timestamp, traceId, executionId | filter executionId = "exec_01JW..."}
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // Trace ID — from header or generate
            String traceId = request.getHeader("X-Trace-Id");
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            }
            MDC.put("traceId", traceId);

            // User ID
            String userId = request.getHeader("X-User-Id");
            if (userId != null && !userId.isBlank()) {
                MDC.put("userId", userId);
            }

            // Request ID
            String requestId = request.getHeader("X-Request-Id");
            if (requestId != null && !requestId.isBlank()) {
                MDC.put("requestId", requestId);
            }

            // Propagate trace ID in response header
            response.setHeader("X-Trace-Id", traceId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
