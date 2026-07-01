package com.example.agentdemo.security;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_rate_limit_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword",
        "demo.security.dev-token.enabled=true",
        "demo.security.rate-limit.requests-per-minute=1"
})
@AutoConfigureMockMvc
class ApiRateLimitIntegrationTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rateLimitsRepeatedAiRequestsByPrincipalAndPath() throws Exception {
        String token = bearer(List.of("chat.execute"));
        String body = "{\"message\":\"hello\"}";

        MvcResult first = mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isNotEqualTo(429);
        mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void rateLimitsRepeatedDocumentUploadsByPrincipalAndPath() throws Exception {
        String token = bearer(List.of("rag.write"));
        String body = "{\"title\":\"doc\",\"content\":\"hello world\"}";

        MvcResult first = mockMvc.perform(post("/api/rag/documents")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isNotEqualTo(429);
        mockMvc.perform(post("/api/rag/documents")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void rateLimitsRepeatedDevTokenMintingByRemoteAddress() throws Exception {
        MvcResult first = mockMvc.perform(get("/api/auth/dev-token"))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isNotEqualTo(429);
        mockMvc.perform(get("/api/auth/dev-token"))
                .andExpect(status().isTooManyRequests());
    }

    private String bearer(List<String> scopes) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("rate-limit-user")
                .issuer("agent-backend-demo")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", String.join(" ", scopes))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return "Bearer " + jwt.serialize();
    }

}
