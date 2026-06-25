package com.example.agentdemo.security;

import com.example.agentdemo.AgentBackendDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * In {@code demo.security.jwt-mode=issuer} the local HMAC decoder must not be registered, so the
 * app boots with no {@code demo.security.jwt-secret} and Spring Boot auto-configures a JWK-set
 * decoder instead. The jwk-set-uri is resolved lazily on first token validation, so the context
 * loads without any network call. (Reviewer Critical #1 / DoD: issuer mode needs no HMAC secret.)
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.security.jwt-mode=issuer",
        "demo.security.jwt-secret=",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.example/.well-known/jwks.json",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_issuer_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class IssuerModeSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void localHmacDecoderIsNotRegisteredInIssuerMode() {
        assertThat(context.containsBean("hmacJwtDecoder")).isFalse();
    }

    @Test
    void securityStillRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/runs"))
                .andExpect(status().isUnauthorized());
    }

}
