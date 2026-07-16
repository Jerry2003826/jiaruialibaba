package com.example.agentdemo.config;

import com.example.agentdemo.app.apikey.AppApiKeyAuthenticationToken;
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
import java.util.regex.Pattern;

final class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final Pattern APP_RUNTIME_ENDPOINT = Pattern.compile("^/api/apps/[^/]+/(run|chat|chat/stream)$");
    private static final Pattern WORKFLOW_PUBLISH_ENDPOINT =
            Pattern.compile("^/api/workflows/definitions/[^/]+/publish$");

    private final boolean enabled;
    private final ApiRateLimiter rateLimiter;

    ApiRateLimitFilter(boolean enabled, ApiRateLimiter rateLimiter) {
        this.enabled = enabled;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !isRateLimitedEndpoint(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!rateLimiter.allow(rateLimitKey(request))) {
            writeRateLimitResponse(response);
            return;
        }
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
                || isAppRuntimeEndpoint(path)
                || path.equals("/api/workflows/run")
                || path.equals("/api/workflows/spec-drafts")
                || path.equals("/api/workflows/generate")
                || path.equals("/api/workflows/generate/stream")
                || path.equals("/api/workflows/governance/evaluate")
                || isWorkflowPublishEndpoint(path);
    }

    private boolean isAppRuntimeEndpoint(String path) {
        return APP_RUNTIME_ENDPOINT.matcher(path).matches();
    }

    private boolean isWorkflowPublishEndpoint(String path) {
        return WORKFLOW_PUBLISH_ENDPOINT.matcher(path).matches();
    }

    private String rateLimitKey(HttpServletRequest request) {
        return principalKey(request) + "|" + request.getMethod() + "|" + rateLimitBucket(request);
    }

    private String rateLimitBucket(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (isWorkflowPublishEndpoint(path)) {
            return "/api/workflows/definitions/*/publish";
        }
        return path;
    }

    private String principalKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof AppApiKeyAuthenticationToken appApiKeyAuthentication
                && authentication.isAuthenticated()
                && StringUtils.hasText(appApiKeyAuthentication.getKeyId())) {
            return "principal:app-key:" + appApiKeyAuthentication.getKeyId();
        }
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

}
