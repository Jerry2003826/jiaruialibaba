package com.example.agentdemo.security;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.common.BusinessException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * Mints short-lived HS256 tokens so the bundled local workbench can authenticate against the
 * secured API without a full OIDC provider. Only registered when
 * {@code demo.security.dev-token.enabled=true} and only mints tokens in
 * {@code demo.security.jwt-mode=hmac}. Disable it for production deployments and front the UI
 * with a real identity provider (issuer mode) instead.
 *
 * <p>{@code @Profile("!prod")} is a hard, fail-safe guard, and the property gate is opt-in. A
 * deployment must explicitly enable this anonymous workbench token endpoint.
 */
@RestController
@RequestMapping("/api/auth")
@Profile("!prod")
@ConditionalOnProperty(prefix = "demo.security.dev-token", name = "enabled", havingValue = "true",
        matchIfMissing = false)
public class DevAuthController {

    /** Scopes the workbench needs to drive every panel. Keep in sync with {@code SecurityConfig}. */
    static final List<String> WORKBENCH_SCOPES = List.of(
            "health.read", "chat.execute", "agent.execute", "rag.read", "rag.write", "rag.query",
            "order.read", "order.write", "tool.read",
            "workflow.read", "workflow.edit", "workflow.run", "workflow.publish", "trace.read", "audit.read");

    private final String jwtMode;
    private final String jwtSecret;
    private final long ttlMinutes;

    public DevAuthController(@Value("${demo.security.jwt-mode:hmac}") String jwtMode,
            @Value("${demo.security.jwt-secret:}") String jwtSecret,
            @Value("${demo.security.dev-token.ttl-minutes:120}") long ttlMinutes) {
        this.jwtMode = jwtMode;
        this.jwtSecret = jwtSecret;
        this.ttlMinutes = ttlMinutes;
    }

    @GetMapping("/dev-token")
    public ApiResponse<DevTokenResponse> devToken() {
        if (!"hmac".equalsIgnoreCase(jwtMode)) {
            throw new BusinessException("DEV_TOKEN_UNAVAILABLE",
                    "Dev token is only available when demo.security.jwt-mode=hmac");
        }
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new BusinessException("DEV_TOKEN_UNAVAILABLE",
                    "demo.security.jwt-secret must be at least 32 bytes to mint a dev token");
        }
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(ttlMinutes, ChronoUnit.MINUTES);
        return ApiResponse.ok(new DevTokenResponse(mint(keyBytes, issuedAt, expiresAt), "Bearer", expiresAt,
                WORKBENCH_SCOPES));
    }

    private String mint(byte[] keyBytes, Instant issuedAt, Instant expiresAt) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject("workbench-dev")
                    .issuer("agent-backend-demo")
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .claim("scope", String.join(" ", WORKBENCH_SCOPES))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(keyBytes));
            return jwt.serialize();
        }
        catch (JOSEException ex) {
            throw new BusinessException("DEV_TOKEN_SIGNING_FAILED", "Failed to sign dev token", ex);
        }
    }

    public record DevTokenResponse(String token, String tokenType, Instant expiresAt, List<String> scopes) {
    }

}
