package com.example.agentdemo.config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import java.util.Arrays;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Built-in insecure secret used as the default in application.yml so the demo boots
     * without extra setup. Production deployments must override it or switch to issuer mode.
     */
    static final String INSECURE_DEFAULT_SECRET = "dev-local-insecure-jwt-secret-change-me-0123456789";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/auth/dev-token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/runs/**").hasAuthority("SCOPE_trace.read")
                        .requestMatchers(HttpMethod.POST, "/api/rag/documents").hasAuthority("SCOPE_rag.write")
                        .requestMatchers(HttpMethod.DELETE, "/api/rag/documents/**").hasAuthority("SCOPE_rag.write")
                        .requestMatchers(HttpMethod.GET, "/api/rag/documents/**").hasAuthority("SCOPE_rag.read")
                        .requestMatchers(HttpMethod.GET, "/api/rag/documents").hasAuthority("SCOPE_rag.read")
                        .requestMatchers(HttpMethod.POST, "/api/rag/chat").hasAuthority("SCOPE_rag.query")
                        .requestMatchers(HttpMethod.POST, "/api/workflows/run").hasAuthority("SCOPE_workflow.run")
                        .requestMatchers(HttpMethod.POST, "/api/workflows/definitions/*/publish")
                        .hasAuthority("SCOPE_workflow.publish")
                        .requestMatchers(HttpMethod.GET, "/api/workflows/**").hasAuthority("SCOPE_workflow.read")
                        .requestMatchers("/api/workflows/**").hasAuthority("SCOPE_workflow.edit")
                        .requestMatchers("/api/agent/**").hasAuthority("SCOPE_agent.execute")
                        .requestMatchers("/api/chat/**", "/api/chat").hasAuthority("SCOPE_chat.execute")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * Local/demo HS256 decoder. Only registered in {@code demo.security.jwt-mode=hmac}
     * (the default). In {@code issuer} mode this bean is absent, so Spring Boot's resource
     * server auto-configuration provides the issuer-uri / jwk-set-uri decoder instead — the
     * property gate avoids preempting that standard decoder.
     */
    @Bean
    @ConditionalOnProperty(prefix = "demo.security", name = "jwt-mode", havingValue = "hmac", matchIfMissing = true)
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder hmacJwtDecoder(@Value("${demo.security.jwt-secret:}") String jwtSecret, Environment environment) {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException(
                    "demo.security.jwt-secret must be configured when demo.security.jwt-mode=hmac");
        }
        byte[] keyBytes = jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("demo.security.jwt-secret must be at least 32 bytes for HS256");
        }
        if (INSECURE_DEFAULT_SECRET.equals(jwtSecret)) {
            boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod");
            if (prod) {
                throw new IllegalStateException("Refusing to start with the built-in insecure demo JWT secret "
                        + "under the 'prod' profile. Set DEMO_SECURITY_JWT_SECRET or use demo.security.jwt-mode=issuer.");
            }
            log.warn("Using the built-in INSECURE demo JWT secret. Override DEMO_SECURITY_JWT_SECRET "
                    + "or switch to demo.security.jwt-mode=issuer before deploying anywhere shared.");
        }
        SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

}
