package com.example.agentdemo.app;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.app.dto.AppResponse;
import com.example.agentdemo.app.dto.CreateAppRequest;
import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowDefinitionResponse;
import com.example.agentdemo.workflow.WorkflowDefinitionSaveRequest;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Security rules for the app API: management endpoints require app.read/app.write, runtime
 * endpoints require app.run, and unpublished apps cannot be invoked when published runs are
 * required.
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_app_sec_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class AppSecurityTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AppService appService;

    @Autowired
    private WorkflowDefinitionService workflowDefinitionService;

    @Test
    void appListRequiresAppReadScope() throws Exception {
        mockMvc.perform(get("/api/apps")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/apps").header(HttpHeaders.AUTHORIZATION, bearer(List.of("chat.execute"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/apps").header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.read"))))
                .andExpect(status().isOk());
    }

    @Test
    void createRequiresAppWriteScope() throws Exception {
        mockMvc.perform(post("/api/apps")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"type\":\"CHAT\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void runtimeEndpointRequiresAppRunScope() throws Exception {
        // app.read is not enough for a runtime call.
        mockMvc.perform(post("/api/apps/app-x/run")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unpublishedAppRunIsRejectedOverHttp() throws Exception {
        String createBody = mockMvc.perform(post("/api/apps")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Draft App\",\"type\":\"CHAT\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String appId = appIdOf(createBody);

        // Draft app: runtime call is rejected (400 APP_NOT_PUBLISHED) even with the correct scope.
        mockMvc.perform(post("/api/apps/" + appId + "/chat")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.run")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsOversizedSystemPrompt() throws Exception {
        mockMvc.perform(post("/api/apps")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Too Big",
                                  "type": "CHAT",
                                  "config": {
                                    "systemPrompt": "%s"
                                  }
                                }
                                """.formatted("x".repeat(16001))))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("VALIDATION_ERROR", "config.systemPrompt"));
    }

    @Test
    void createRejectsTooManyKnowledgeBaseIds() throws Exception {
        String kbIds = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> "\"kb-%02d\"".formatted(i))
                .collect(java.util.stream.Collectors.joining(","));
        mockMvc.perform(post("/api/apps")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "KB Overflow",
                                  "type": "CHAT",
                                  "config": {
                                    "knowledgeBaseIds": [%s]
                                  }
                                }
                                """.formatted(kbIds)))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("VALIDATION_ERROR", "config.knowledgeBaseIds"));
    }

    @Test
    void createRejectsTooLargeMemoryWindow() throws Exception {
        mockMvc.perform(post("/api/apps")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Memory Overflow",
                                  "type": "CHAT",
                                  "config": {
                                    "memoryMaxMessages": 101
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("VALIDATION_ERROR", "config.memoryMaxMessages"));
    }

    @Test
    void createAcceptsValidBoundaryConfig() throws Exception {
        mockMvc.perform(post("/api/apps")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Valid Chat",
                                  "type": "CHAT",
                                  "config": {
                                    "systemPrompt": "%s",
                                    "model": "%s",
                                    "memoryMaxMessages": 100,
                                    "knowledgeBaseIds": ["kb-001"]
                                  }
                                }
                                """.formatted("x".repeat(16000), "m".repeat(128))))
                .andExpect(status().isOk());
    }

    @Test
    void runRejectsOversizedInputPayload() throws Exception {
        AppResponse app = appService.publish(appService.create(new CreateAppRequest("Flow App", null, AppType.WORKFLOW,
                null, publishedWorkflow(), null)).appId());

        mockMvc.perform(post("/api/apps/" + app.appId() + "/run")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.run")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "input": {
                                    "message": "%s"
                                  }
                                }
                                """.formatted("x".repeat(70_000))))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains("VALIDATION_ERROR", "input"));
    }

    private String appIdOf(String responseBody) {
        int idx = responseBody.indexOf("\"appId\":\"");
        int start = idx + "\"appId\":\"".length();
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
    }

    private String publishedWorkflow() {
        WorkflowDefinitionResponse saved = workflowDefinitionService.save(
                new WorkflowDefinitionSaveRequest("Flow", null, simpleWorkflow()));
        workflowDefinitionService.publish(saved.definitionId());
        return saved.definitionId();
    }

    private WorkflowDefinition simpleWorkflow() {
        return new WorkflowDefinition(
                List.of(new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
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
