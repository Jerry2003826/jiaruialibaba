package com.example.agentdemo.tool;

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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1-4: console tool dry-run executes local tools, reports not-found, is traced, and is guarded by
 * {@code SCOPE_tool.execute}.
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_tooltest;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class ToolTestServiceIntegrationTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private ToolTestService toolTestService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void dryRunExecutesLocalTool() {
        ToolExecutionLog log = toolTestService.test("getCurrentTime", Map.of());
        assertThat(log.toolName()).isEqualTo("getCurrentTime");
        assertThat(log.succeeded()).isTrue();
    }

    @Test
    void dryRunReportsUnknownTool() {
        ToolExecutionLog log = toolTestService.test("does-not-exist", Map.of());
        assertThat(log.succeeded()).isFalse();
        assertThat(log.errorCategory()).isEqualTo(ToolExecutionLog.ERROR_TOOL_NOT_FOUND);
    }

    @Test
    void testEndpointRequiresToolExecuteScope() throws Exception {
        mockMvc.perform(post("/api/tools/getCurrentTime/test")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/tools/getCurrentTime/test")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("tool.read")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tools/getCurrentTime/test")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("tool.execute")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"arguments\":{}}"))
                .andExpect(status().isOk());
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
