package com.example.agentdemo.app.apikey;

import com.example.agentdemo.audit.AuditActorResolver;
import com.example.agentdemo.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Authenticates runtime app API keys presented via {@code X-App-API-Key} or
 * {@code Authorization: Bearer app_...}. A valid key may only reach the runtime endpoints
 * ({@code /run}, {@code /chat}, {@code /chat/stream}) of its own app; anything else is rejected
 * with 403. When the key arrives on the Authorization header, the header is hidden from downstream
 * so the JWT resource-server filter does not try (and fail) to parse it as a JWT.
 */
@Component
public class AppApiKeyAuthenticationFilter extends OncePerRequestFilter {

    /** Header for supplying an app API key without using Authorization. */
    public static final String API_KEY_HEADER = "X-App-API-Key";

    private static final Pattern RUNTIME_PATH = Pattern.compile("^/api/apps/([^/]+)/(run|chat|chat/stream)$");

    private final AppApiKeyRepository appApiKeyRepository;
    private final ObjectMapper objectMapper;

    public AppApiKeyAuthenticationFilter(AppApiKeyRepository appApiKeyRepository, ObjectMapper objectMapper) {
        this.appApiKeyRepository = appApiKeyRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        PresentedKey presented = extractKey(request);
        if (presented == null) {
            filterChain.doFilter(request, response);
            return;
        }

        AppApiKeyEntity key = appApiKeyRepository
                .findByKeyHashAndStatus(ApiKeySecrets.hash(presented.value()), AppApiKeyStatus.ACTIVE)
                .orElse(null);
        if (key == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, "API_KEY_INVALID", "Invalid or revoked API key");
            return;
        }

        String path = pathWithinApplication(request);
        Matcher matcher = RUNTIME_PATH.matcher(path);
        if (!matcher.matches() || !matcher.group(1).equals(key.getAppId())) {
            writeError(response, HttpStatus.FORBIDDEN, "API_KEY_FORBIDDEN",
                    "This API key may only call its own app's runtime endpoints");
            return;
        }

        AppApiKeyAuthenticationToken authentication = new AppApiKeyAuthenticationToken(key.getOwnerId(),
                key.getAppId(), key.getKeyId(), scopeAuthorities(key.getScopes()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(AuditActorResolver.APP_API_KEY_ATTRIBUTE, key.getKeyId());
        touchLastUsed(key);

        // Hide the Authorization header when the key arrived there, so the JWT filter is a no-op.
        HttpServletRequest downstream = presented.fromAuthorization()
                ? new AuthorizationStrippingRequest(request)
                : request;
        filterChain.doFilter(downstream, response);
    }

    private PresentedKey extractKey(HttpServletRequest request) {
        String headerKey = request.getHeader(API_KEY_HEADER);
        if (StringUtils.hasText(headerKey) && ApiKeySecrets.looksLikeApiKey(headerKey.trim())) {
            return new PresentedKey(headerKey.trim(), false);
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            String token = authorization.substring("Bearer ".length()).trim();
            if (ApiKeySecrets.looksLikeApiKey(token)) {
                return new PresentedKey(token, true);
            }
        }
        return null;
    }

    private void touchLastUsed(AppApiKeyEntity key) {
        try {
            key.markUsed(Instant.now());
            appApiKeyRepository.save(key);
        }
        catch (RuntimeException ex) {
            // last_used_at is best-effort; never fail the request over it.
            logger.debug("Failed to update API key last_used_at", ex);
        }
    }

    private List<org.springframework.security.core.GrantedAuthority> scopeAuthorities(String scopes) {
        String authorities = Arrays.stream(scopes.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(scope -> "SCOPE_" + scope)
                .collect(Collectors.joining(","));
        return AuthorityUtils.commaSeparatedStringToAuthorityList(authorities);
    }

    private String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, message)));
    }

    private record PresentedKey(String value, boolean fromAuthorization) {
    }

    /** Wraps a request so the Authorization header is invisible downstream. */
    private static final class AuthorizationStrippingRequest extends HttpServletRequestWrapper {

        AuthorizationStrippingRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) {
                return java.util.Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }
    }

}
