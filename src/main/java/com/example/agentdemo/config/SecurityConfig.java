package com.example.agentdemo.config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

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
    @Order(0)
    SecurityFilterChain h2ConsoleSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/h2-console", "/h2-console/**")
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiRateLimitFilter apiRateLimitFilter) throws Exception {
        return http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health").hasAuthority("SCOPE_health.read")
                        .requestMatchers(HttpMethod.GET, "/api/auth/dev-token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/runs/**").hasAuthority("SCOPE_trace.read")
                        .requestMatchers(HttpMethod.POST, "/api/rag/documents").hasAuthority("SCOPE_rag.write")
                        .requestMatchers(HttpMethod.PUT, "/api/rag/documents/**").hasAuthority("SCOPE_rag.write")
                        .requestMatchers(HttpMethod.DELETE, "/api/rag/documents/**").hasAuthority("SCOPE_rag.write")
                        .requestMatchers(HttpMethod.GET, "/api/rag/documents/**").hasAuthority("SCOPE_rag.read")
                        .requestMatchers(HttpMethod.GET, "/api/rag/documents").hasAuthority("SCOPE_rag.read")
                        .requestMatchers(HttpMethod.POST, "/api/rag/chat").hasAuthority("SCOPE_rag.query")
                        .requestMatchers(HttpMethod.GET, "/api/orders", "/api/orders/**")
                        .hasAuthority("SCOPE_order.read")
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasAuthority("SCOPE_order.write")
                        .requestMatchers(HttpMethod.PUT, "/api/orders/**").hasAuthority("SCOPE_order.write")
                        .requestMatchers(HttpMethod.DELETE, "/api/orders/**").hasAuthority("SCOPE_order.write")
                        .requestMatchers(HttpMethod.GET, "/api/tools", "/api/tools/**")
                        .hasAuthority("SCOPE_tool.read")
                        .requestMatchers(HttpMethod.POST, "/api/workflows/run").hasAuthority("SCOPE_workflow.run")
                        .requestMatchers(HttpMethod.POST, "/api/workflows/definitions/*/publish")
                        .hasAuthority("SCOPE_workflow.publish")
                        .requestMatchers(HttpMethod.GET, "/api/workflows/**").hasAuthority("SCOPE_workflow.read")
                        .requestMatchers("/api/workflows/**").hasAuthority("SCOPE_workflow.edit")
                        .requestMatchers("/api/agent/**").hasAuthority("SCOPE_agent.execute")
                        .requestMatchers("/api/chat/**", "/api/chat").hasAuthority("SCOPE_chat.execute")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .addFilterAfter(apiRateLimitFilter, BearerTokenAuthenticationFilter.class)
                .build();
    }

    @Bean
    ApiRateLimitFilter apiRateLimitFilter(
            @Value("${demo.security.rate-limit.enabled:true}") boolean enabled,
            @Value("${demo.security.rate-limit.requests-per-minute:120}") int requestsPerMinute) {
        return new ApiRateLimitFilter(enabled, requestsPerMinute);
    }

    @Bean
    FilterRegistrationBean<ApiRateLimitFilter> apiRateLimitFilterRegistration(ApiRateLimitFilter filter) {
        FilterRegistrationBean<ApiRateLimitFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
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
    JwtDecoder hmacJwtDecoder(@Value("${demo.security.jwt-secret:}") String jwtSecret,
            @Value("${demo.security.jwt-issuer:agent-backend-demo}") String jwtIssuer,
            @Value("${demo.security.jwt-audience:}") String jwtAudience,
            Environment environment) {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException(
                    "demo.security.jwt-secret must be configured when demo.security.jwt-mode=hmac");
        }
        byte[] keyBytes = jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("demo.security.jwt-secret must be at least 32 bytes for HS256");
        }
        if (INSECURE_DEFAULT_SECRET.equals(jwtSecret)) {
            boolean dev = environment.acceptsProfiles(Profiles.of("dev"));
            if (!dev) {
                throw new IllegalStateException("Refusing to start with the built-in insecure demo JWT secret "
                        + "outside the 'dev' profile. Set DEMO_SECURITY_JWT_SECRET or use demo.security.jwt-mode=issuer.");
            }
            log.warn("Using the built-in INSECURE demo JWT secret. Override DEMO_SECURITY_JWT_SECRET "
                    + "or switch to demo.security.jwt-mode=issuer before deploying anywhere shared.");
        }
        SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(jwtValidator(jwtIssuer, jwtAudience));
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> jwtValidator(String jwtIssuer, String jwtAudience) {
        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(jwtIssuer);
        if (!StringUtils.hasText(jwtAudience)) {
            return validator;
        }
        OAuth2TokenValidator<Jwt> audienceValidator = token -> token.getAudience().contains(jwtAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Missing required JWT audience", null));
        return new DelegatingOAuth2TokenValidator<>(validator, audienceValidator);
    }

}
