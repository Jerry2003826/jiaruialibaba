package com.example.agentdemo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_TRACKED_WINDOWS = 10_000;

    private final boolean enabled;
    private final int requestsPerMinute;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    ApiRateLimitFilter(boolean enabled, int requestsPerMinute) {
        this.enabled = enabled;
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || requestsPerMinute <= 0 || !isRateLimitedEndpoint(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long currentWindow = System.currentTimeMillis() / WINDOW_MS;
        Window window = windows.compute(rateLimitKey(request), (key, existing) -> {
            if (existing == null || existing.window() != currentWindow) {
                return new Window(currentWindow, 1);
            }
            existing.increment();
            return existing;
        });
        if (window.count() > requestsPerMinute) {
            writeRateLimitResponse(response);
            return;
        }
        cleanupOldWindows(currentWindow);
        filterChain.doFilter(request, response);
    }

    private boolean isRateLimitedEndpoint(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method)) {
            return path.equals("/api/auth/dev-token");
        }
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return path.equals("/api/chat")
                || path.equals("/api/chat/stream")
                || path.equals("/api/rag/chat")
                || path.equals("/api/rag/documents")
                || path.startsWith("/api/agent/")
                || path.equals("/api/workflows/run")
                || path.equals("/api/workflows/generate")
                || path.equals("/api/workflows/generate/stream");
    }

    private String rateLimitKey(HttpServletRequest request) {
        return principalKey(request) + "|" + request.getMethod() + "|" + request.getRequestURI();
    }

    private String principalKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && StringUtils.hasText(authentication.getName())) {
            return "principal:" + authentication.getName();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization)) {
            return "authorization:" + Integer.toHexString(authorization.hashCode());
        }
        return "remote:" + request.getRemoteAddr();
    }

    private void writeRateLimitResponse(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, "60");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("""
                {"success":false,"code":"RATE_LIMITED","message":"Too many requests. Please retry later.","data":null,"timestamp":"%s"}\
                """.formatted(Instant.now()));
    }

    private void cleanupOldWindows(long currentWindow) {
        if (windows.size() <= MAX_TRACKED_WINDOWS) {
            return;
        }
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().window() < currentWindow) {
                iterator.remove();
            }
        }
    }

    private static final class Window {

        private final long window;
        private int count;

        private Window(long window, int count) {
            this.window = window;
            this.count = count;
        }

        private long window() {
            return window;
        }

        private int count() {
            return count;
        }

        private void increment() {
            count++;
        }

    }

}
