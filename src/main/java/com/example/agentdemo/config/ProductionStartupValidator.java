package com.example.agentdemo.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Fails fast on boot under the {@code prod} profile when the deployment is not hardened for a
 * real, internet-facing environment. Every violation is collected so an operator sees the full
 * list in one startup error instead of fixing them one at a time.
 *
 * <p>Runs at {@link Ordered#HIGHEST_PRECEDENCE} so its aggregated, prod-specific message is the
 * one surfaced before the more targeted {@link AlibabaStackValidator} runs.
 */
@Component
@Profile("prod")
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "demo.production-validation", name = "enabled", havingValue = "true",
        matchIfMissing = true)
public class ProductionStartupValidator implements ApplicationRunner {

    private final Environment environment;

    public ProductionStartupValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = collectViolations();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Production startup validation failed (profile 'prod'):\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    List<String> collectViolations() {
        List<String> violations = new ArrayList<>();
        validateDatasource(violations);
        validateSecurity(violations);
        validateRuntimePolicy(violations);
        validateModelAndVector(violations);
        return violations;
    }

    private void validateDatasource(List<String> violations) {
        String url = environment.getProperty("spring.datasource.url", "");
        String driver = environment.getProperty("spring.datasource.driver-class-name", "");
        if (url.startsWith("jdbc:h2:") || driver.toLowerCase().contains("h2")) {
            violations.add("H2 datasource must not be used in prod; configure a PostgreSQL "
                    + "spring.datasource.url (activate the 'postgres' profile alongside 'prod').");
        }
        else if (!url.startsWith("jdbc:postgresql:")) {
            violations.add("A PostgreSQL datasource is required in prod "
                    + "(spring.datasource.url must start with jdbc:postgresql:).");
        }
    }

    private void validateSecurity(List<String> violations) {
        if (environment.getProperty("demo.security.dev-token.enabled", Boolean.class, false)) {
            violations.add("demo.security.dev-token.enabled must be false in prod "
                    + "(front the console with a real identity provider).");
        }
        String jwtMode = environment.getProperty("demo.security.jwt-mode", "hmac");
        if ("issuer".equalsIgnoreCase(jwtMode)) {
            String issuerUri = environment.getProperty(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri", "");
            String jwkSetUri = environment.getProperty(
                    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", "");
            if (!StringUtils.hasText(issuerUri) && !StringUtils.hasText(jwkSetUri)) {
                violations.add("issuer mode requires spring.security.oauth2.resourceserver.jwt.issuer-uri "
                        + "or jwk-set-uri.");
            }
        }
        else if ("hmac".equalsIgnoreCase(jwtMode)) {
            String secret = environment.getProperty("demo.security.jwt-secret", "");
            if (SecurityConfig.INSECURE_DEFAULT_SECRET.equals(secret)) {
                violations.add("The built-in insecure demo JWT secret must not be used in prod; "
                        + "set DEMO_SECURITY_JWT_SECRET or switch to issuer mode.");
            }
            else if (!StringUtils.hasText(secret)
                    || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
                violations.add("hmac mode requires a strong random demo.security.jwt-secret of at least "
                        + "32 bytes (issuer mode is recommended for prod).");
            }
        }
        else {
            violations.add("demo.security.jwt-mode must be 'issuer' (recommended) or 'hmac', got: " + jwtMode);
        }
    }

    private void validateRuntimePolicy(List<String> violations) {
        if (!environment.getProperty("demo.alibaba.strict-mode", Boolean.class, false)) {
            violations.add("demo.alibaba.strict-mode must be true in prod.");
        }
        if (environment.getProperty("demo.ai.fallback-enabled", Boolean.class, true)) {
            violations.add("demo.ai.fallback-enabled must be false in prod (no fake LLM fallback).");
        }
        if (environment.getProperty("demo.rag.keyword-fallback-enabled", Boolean.class, false)) {
            violations.add("demo.rag.keyword-fallback-enabled must be false in prod.");
        }
        String retriever = environment.getProperty("demo.rag.retriever", "");
        if (!"dashvector".equalsIgnoreCase(retriever)) {
            violations.add("demo.rag.retriever must be 'dashvector' in prod.");
        }
        if (!environment.getProperty("demo.workflow.require-published-for-run", Boolean.class, false)) {
            violations.add("demo.workflow.require-published-for-run must be true in prod.");
        }
        if (environment.getProperty("demo.workflow.allow-inline-run", Boolean.class, false)) {
            violations.add("demo.workflow.allow-inline-run must be false in prod.");
        }
        if (!environment.getProperty("demo.app.require-published-for-run", Boolean.class, false)) {
            violations.add("demo.app.require-published-for-run must be true in prod.");
        }
    }

    private void validateModelAndVector(List<String> violations) {
        String apiKey = environment.getProperty("spring.ai.dashscope.api-key", "");
        if (!StringUtils.hasText(apiKey) || "your-api-key".equals(apiKey)) {
            violations.add("spring.ai.dashscope.api-key (DashScope chat + embedding) must be configured in prod.");
        }
        if (!StringUtils.hasText(environment.getProperty("demo.ai.embedding-model"))) {
            violations.add("demo.ai.embedding-model must be configured in prod.");
        }
        if (!StringUtils.hasText(environment.getProperty("demo.dashvector.endpoint"))) {
            violations.add("demo.dashvector.endpoint must be configured in prod.");
        }
        if (!StringUtils.hasText(environment.getProperty("demo.dashvector.api-key"))) {
            violations.add("demo.dashvector.api-key must be configured in prod.");
        }
    }

}
