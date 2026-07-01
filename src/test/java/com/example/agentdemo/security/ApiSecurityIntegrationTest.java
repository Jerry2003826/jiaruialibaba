package com.example.agentdemo.security;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_security_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "spring.h2.console.enabled=true",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class ApiSecurityIntegrationTest {

    /** Matches demo.security.jwt-secret in src/test/resources/application.properties. */
    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthRequiresHealthReadScope() throws Exception {
        mockMvc.perform(get("/api/health")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("chat.execute"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/health")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("health.read"))))
                .andExpect(status().isOk());
    }

    @Test
    void h2ConsoleIsExplicitlyDeniedEvenWhenEnabled() throws Exception {
        mockMvc.perform(get("/h2-console"))
                .andExpect(status().isForbidden());
    }

    @Test
    void ragDocumentsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/rag/documents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void workflowRunRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/workflows/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void traceRunsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void toolChatRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/agent/tool-chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assistantChatWorkflowBindingRequiresWorkflowRunScope() throws Exception {
        mockMvc.perform(post("/api/agent/assistant-chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("agent.execute")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "run workflow",
                                  "workflowDefinition": {
                                    "nodes": [
                                      {"id": "start", "type": "start", "config": {}},
                                      {"id": "end", "type": "end", "config": {}}
                                    ],
                                    "edges": [
                                      {"from": "start", "to": "end"}
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void traceRunsAllowedWithCorrectScope() throws Exception {
        mockMvc.perform(get("/api/runs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("trace.read"))))
                .andExpect(status().isOk());
    }

    @Test
    void traceRunsForbiddenWithWrongScope() throws Exception {
        mockMvc.perform(get("/api/runs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("chat.execute"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void ragDocumentUpdateRequiresRagWriteScope() throws Exception {
        mockMvc.perform(put("/api/rag/documents/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("rag.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"content\":\"hello world\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void orderReadRequiresOrderReadScope() throws Exception {
        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("chat.execute"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void orderWriteRequiresOrderWriteScope() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("order.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "orderId": "999999990",
                                  "customerName": "Test",
                                  "status": "CREATED",
                                  "paid": false,
                                  "amount": 1.00,
                                  "currency": "CNY"
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void toolCatalogRequiresToolReadScope() throws Exception {
        mockMvc.perform(get("/api/tools")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("chat.execute"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void devTokenEndpointIsNotRegisteredOutsideDevProfile() throws Exception {
        mockMvc.perform(get("/api/auth/dev-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void hmacTokenWithoutExpectedIssuerIsRejected() throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-user")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", "trace.read")
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/runs").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.serialize()))
                .andExpect(status().isUnauthorized());
    }

    private String bearer(List<String> scopes) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-user")
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
