package com.example.agentdemo.config;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
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

    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    JwtDecoder hmacJwtDecoder(@Value("${demo.security.jwt-secret:}") String jwtSecret) {
        if (!StringUtils.hasText(jwtSecret)) {
            throw new IllegalStateException(
                    "demo.security.jwt-secret must be configured when no OAuth2 issuer or JWK set is configured");
        }
        byte[] keyBytes = jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("demo.security.jwt-secret must be at least 32 bytes for HS256");
        }
        SecretKey secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

}
