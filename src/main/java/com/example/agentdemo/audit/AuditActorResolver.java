package com.example.agentdemo.audit;

import com.example.agentdemo.security.SecurityIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the current {@link AuditActor} from Spring Security and the HTTP request. Runtime app
 * API-key requests advertise themselves via a request attribute (set by the API-key filter) so
 * this resolver does not need a compile-time dependency on the api-key authentication token.
 */
@Component
public class AuditActorResolver {

    /** Request attribute carrying the runtime API key id, set by the app API-key auth filter. */
    public static final String APP_API_KEY_ATTRIBUTE = "com.example.agentdemo.audit.appApiKeyId";

    private static final int MAX_IP_LENGTH = 64;
    private static final int MAX_USER_AGENT_LENGTH = 256;
    private final AuditProperties auditProperties;

    public AuditActorResolver(AuditProperties auditProperties) {
        this.auditProperties = auditProperties;
    }

    public AuditActor resolve() {
        String ownerId = SecurityIdentity.currentOwnerId();
        HttpServletRequest request = currentRequest();
        String ip = clientIp(request);
        String userAgent = truncate(header(request, "User-Agent"), MAX_USER_AGENT_LENGTH);

        Object apiKeyId = request == null ? null : request.getAttribute(APP_API_KEY_ATTRIBUTE);
        if (apiKeyId instanceof String keyId && StringUtils.hasText(keyId)) {
            return new AuditActor(ownerId, AuditActorType.APP_API_KEY, keyId, ip, userAgent);
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return new AuditActor(ownerId, AuditActorType.CONSOLE_JWT, ownerId, ip, userAgent);
        }
        return new AuditActor(ownerId, AuditActorType.SYSTEM, "system", ip, userAgent);
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String remoteAddr = sanitizeForwardedCandidate(request.getRemoteAddr());
        if (auditProperties.isTrustForwardedHeaders()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                // Trusted proxies overwrite X-Forwarded-For with $remote_addr. If a legacy proxy appends
                // $remote_addr to a client-supplied list, the safest recoverable value is the last non-blank one.
                String[] parts = forwarded.split(",");
                for (int i = parts.length - 1; i >= 0; i--) {
                    String candidate = sanitizeForwardedCandidate(parts[i]);
                    if (candidate != null) {
                        return candidate;
                    }
                }
            }
        }
        return remoteAddr;
    }

    private String header(HttpServletRequest request, String name) {
        return request == null ? null : request.getHeader(name);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String sanitizeForwardedCandidate(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            return null;
        }
        String cleaned = value.trim();
        if (!StringUtils.hasText(cleaned) || cleaned.length() > MAX_IP_LENGTH) {
            return null;
        }
        return cleaned;
    }

}
