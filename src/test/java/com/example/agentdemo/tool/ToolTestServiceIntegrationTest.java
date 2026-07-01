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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
@Import(ToolTestServiceIntegrationTest.ToolSchemaValidationTestConfiguration.class)
class ToolTestServiceIntegrationTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";
    private static final String TOOL_TEST_ENDPOINT = "/api/tools/getCurrentTime/test";
    private static final String SCHEMA_VALIDATED_REMOTE = "schemaValidatedRemote";

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
    void dryRunReusesSharedSchemaValidatorForRemoteToolDescriptors() {
        ToolExecutionLog log = toolTestService.test(SCHEMA_VALIDATED_REMOTE, Map.of());

        assertThat(log.succeeded()).isFalse();
        assertThat(log.errorCategory()).isEqualTo(ToolExecutionLog.ERROR_VALIDATION);
        assertThat(log.errorMessage()).isEqualTo("Missing required tool argument: text");
    }

    @Test
    void testEndpointRequiresToolExecuteScope() throws Exception {
        mockMvc.perform(post(TOOL_TEST_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(TOOL_TEST_ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("tool.read")))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(TOOL_TEST_ENDPOINT)
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

    @TestConfiguration
    static class ToolSchemaValidationTestConfiguration {

        @Bean
        ToolProvider schemaValidatedRemoteToolProvider() {
            return new ToolProvider() {
                @Override
                public String providerName() {
                    return "mcp";
                }

                @Override
                public boolean supports(String toolName) {
                    return SCHEMA_VALIDATED_REMOTE.equals(toolName);
                }

                @Override
                public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
                    Instant now = Instant.now();
                    return ToolExecutionLog.success(toolName, arguments, "provider-executed", now, now,
                            tools().getFirst());
                }

                @Override
                public List<ToolDescriptor> tools() {
                    return List.of(new ToolDescriptor(SCHEMA_VALIDATED_REMOTE,
                            "Remote tool used to verify shared schema validation in dry-run mode",
                            "mcp", true, "integration-test", """
                                    {
                                      "type": "object",
                                      "properties": {
                                        "text": {"type": "string"}
                                      },
                                      "required": ["text"]
                                    }
                                    """));
                }
            };
        }

    }

}
