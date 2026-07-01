package com.example.agentdemo.config;

import com.example.agentdemo.AgentBackendDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots the real application under the {@code prod} profile with an H2 datasource and asserts the
 * context refuses to start. Issuer mode with a jwk-set-uri is used so the JWT decoder resolves
 * lazily (no network) and the failure is attributable to {@link ProductionStartupValidator}, not
 * to the local HMAC decoder guard.
 */
class ProductionStartupValidationBootTest {

    @Test
    void prodProfileRefusesToBootWithH2Datasource() {
        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(
                    AgentBackendDemoApplication.class)
                    .profiles("prod")
                    // Command-line args have higher precedence than application.yml so server.port=0
                    // (random free port) actually wins over the bundled default of 8080.
                    .run(
                            "--server.port=0",
                            "--spring.datasource.url=jdbc:h2:mem:prod_boot_guard;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
                            "--spring.datasource.driver-class-name=org.h2.Driver",
                            "--demo.security.jwt-mode=issuer",
                            "--demo.security.jwt-secret=",
                            "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/.well-known/jwks.json",
                            "--spring.flyway.enabled=false")) {
                // Should never reach here: startup must fail.
            }
        }).hasStackTraceContaining("Production startup validation failed")
                .hasStackTraceContaining("H2 datasource must not be used");
    }

}
