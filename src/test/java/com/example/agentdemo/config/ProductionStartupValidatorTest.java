package com.example.agentdemo.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ProductionStartupValidator#collectViolations()} covering each prod
 * hardening rule in isolation. A fully hardened baseline environment is mutated one property at a
 * time so every rule is asserted independently.
 */
class ProductionStartupValidatorTest {

    private static MockEnvironment hardenedEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:postgresql://db:5432/agent_demo");
        env.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
        env.setProperty("spring.datasource.username", "agent_demo_prod");
        env.setProperty("spring.datasource.password", "prod-random-db-password-123456");
        env.setProperty("demo.security.dev-token.enabled", "false");
        env.setProperty("demo.security.jwt-mode", "issuer");
        env.setProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                "https://issuer.example/.well-known/jwks.json");
        env.setProperty("demo.alibaba.strict-mode", "true");
        env.setProperty("demo.ai.fallback-enabled", "false");
        env.setProperty("demo.rag.keyword-fallback-enabled", "false");
        env.setProperty("demo.rag.retriever", "dashvector");
        env.setProperty("demo.workflow.require-published-for-run", "true");
        env.setProperty("demo.workflow.allow-inline-run", "false");
        env.setProperty("demo.app.require-published-for-run", "true");
        env.setProperty("demo.security.rate-limit.backend", "external");
        env.setProperty("spring.ai.dashscope.api-key", "sk-real-key");
        env.setProperty("demo.ai.embedding-model", "text-embedding-v4");
        env.setProperty("demo.dashvector.endpoint", "https://vrs.dashvector.cn");
        env.setProperty("demo.dashvector.api-key", "dv-real-key");
        return env;
    }

    @Test
    void hardenedEnvironmentHasNoViolations() {
        assertThat(new ProductionStartupValidator(hardenedEnvironment()).collectViolations()).isEmpty();
    }

    @Test
    void rejectsH2Datasource() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:h2:mem:agent_demo");
        env.setProperty("spring.datasource.driver-class-name", "org.h2.Driver");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("H2 datasource must not be used"));
    }

    @Test
    void rejectsNonPostgresDatasource() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("spring.datasource.url", "jdbc:mysql://db:3306/agent");
        env.setProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("PostgreSQL datasource is required"));
    }

    @Test
    void rejectsDefaultDatabasePassword() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("spring.datasource.password", "agent_demo");

        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("spring.datasource.password must not use a demo default"));
    }

    @Test
    void rejectsPlaceholderDatabasePassword() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("spring.datasource.password", "change-me-strong-db-password");

        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("spring.datasource.password must not use a demo default"));
    }

    @Test
    void rejectsDevTokenEnabled() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.security.dev-token.enabled", "true");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("demo.security.dev-token.enabled must be false"));
    }

    @Test
    void rejectsBuiltInInsecureSecret() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.security.jwt-mode", "hmac");
        env.setProperty("demo.security.jwt-secret", SecurityConfig.INSECURE_DEFAULT_SECRET);
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("built-in insecure demo JWT secret"));
    }

    @Test
    void rejectsWeakHmacSecret() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.security.jwt-mode", "hmac");
        env.setProperty("demo.security.jwt-secret", "too-short");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("at least 32 bytes"));
    }

    @Test
    void acceptsStrongHmacSecret() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.security.jwt-mode", "hmac");
        env.setProperty("demo.security.jwt-secret", "an-actually-strong-random-secret-value-1234567890");
        assertThat(new ProductionStartupValidator(env).collectViolations()).isEmpty();
    }

    @Test
    void rejectsIssuerModeWithoutIssuerConfig() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.security.jwt-mode", "issuer");
        env.setProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("issuer mode requires"));
    }

    @Test
    void rejectsStrictModeDisabled() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.alibaba.strict-mode", "false");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("demo.alibaba.strict-mode must be true"));
    }

    @Test
    void rejectsFallbackEnabled() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.ai.fallback-enabled", "true");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("demo.ai.fallback-enabled must be false"));
    }

    @Test
    void rejectsInMemoryRateLimitBackendInProduction() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.security.rate-limit.backend", "memory");

        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("demo.security.rate-limit.backend must be external"));
    }

    @Test
    void rejectsInlineWorkflowRun() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.workflow.allow-inline-run", "true");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("demo.workflow.allow-inline-run must be false"));
    }

    @Test
    void rejectsMissingDashScopeKey() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("spring.ai.dashscope.api-key", "");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("spring.ai.dashscope.api-key"));
    }

    @Test
    void rejectsMissingDashVectorConfig() {
        MockEnvironment env = hardenedEnvironment();
        env.setProperty("demo.dashvector.endpoint", "");
        env.setProperty("demo.dashvector.api-key", "");
        assertThat(new ProductionStartupValidator(env).collectViolations())
                .anyMatch(v -> v.contains("demo.dashvector.endpoint"))
                .anyMatch(v -> v.contains("demo.dashvector.api-key"));
    }

}
