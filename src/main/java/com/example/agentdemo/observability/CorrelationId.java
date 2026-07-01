package com.example.agentdemo.observability;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

/**
 * Accessor for the per-request correlation id kept in the SLF4J {@link MDC} under
 * {@link #MDC_KEY}. The id is populated by {@code CorrelationIdFilter} and mirrored into the
 * {@code X-Request-Id} response header so logs, traces and clients can be joined on it.
 */
public final class CorrelationId {

    /** MDC key surfaced by the logging pattern in {@code application.yml}. */
    public static final String MDC_KEY = "requestId";

    /** Response/request header carrying the correlation id. */
    public static final String HEADER = "X-Request-Id";

    /** Legacy/alternative inbound header some proxies use. */
    public static final String ALT_HEADER = "X-Correlation-Id";

    private CorrelationId() {
    }

    /**
     * Returns the current request's correlation id, or {@code null} when no request is in scope.
     *
     * @return the correlation id bound to the current thread, or {@code null}
     */
    public static String get() {
        String value = MDC.get(MDC_KEY);
        return StringUtils.hasText(value) ? value : null;
    }

}
