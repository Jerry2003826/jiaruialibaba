package com.example.agentdemo.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Assigns every request a correlation id, exposes it on the SLF4J {@link MDC} (so it appears in
 * every log line) and echoes it back to the client via the {@code X-Request-Id} response header.
 *
 * <p>An inbound {@code X-Request-Id} / {@code X-Correlation-Id} header is honoured when it looks
 * safe (bounded length, restricted alphabet) so a value cannot be used for header/log injection;
 * otherwise a fresh UUID is generated. The MDC entry is always cleared in a finally block so ids
 * never leak across pooled worker threads.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final int MAX_LENGTH = 64;
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveInbound(request);
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private String resolveInbound(HttpServletRequest request) {
        String candidate = firstHeader(request, CorrelationId.HEADER, CorrelationId.ALT_HEADER);
        if (candidate != null) {
            candidate = candidate.trim();
            if (candidate.length() <= MAX_LENGTH && SAFE.matcher(candidate).matches()) {
                return candidate;
            }
        }
        return UUID.randomUUID().toString();
    }

    private String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String value = request.getHeader(name);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

}
