package com.example.agentdemo.security;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.app.AppConfig;
import com.example.agentdemo.app.AppService;
import com.example.agentdemo.app.AppType;
import com.example.agentdemo.app.apikey.AppApiKeyService;
import com.example.agentdemo.app.apikey.dto.CreateApiKeyRequest;
import com.example.agentdemo.app.dto.AppResponse;
import com.example.agentdemo.app.dto.CreateAppRequest;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
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

    @Autowired
    private AppService appService;

    @Autowired
    private AppApiKeyService appApiKeyService;

    @Autowired
    private WorkflowDefinitionService workflowDefinitionService;

    @MockBean
    private AiModelService aiModelService;

    @Test
    void rateLimitsRepeatedAiRequestsByPrincipalAndPath() throws Exception {
        when(aiModelService.generate(anyString(), anyList(), anyString()))
                .thenReturn(AiModelResult.ok("hello", null));
        when(aiModelService.generate(anyString(), anyList(), anyString(), any()))
                .thenReturn(AiModelResult.ok("hello", null));
        String token = bearer(List.of("chat.execute"));
        String body = "{\"message\":\"hello\"}";

        MvcResult first = mockMvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
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

    @Test
    void rateLimitsRepeatedAppChatRequests() throws Exception {
        when(aiModelService.generate(anyString(), anyList(), anyString(), any()))
                .thenReturn(AiModelResult.ok("hello from app", null));
        RuntimeAppKey runtimeAppKey = runtimeApiKeyForPublishedChatApp("Rate Limited Chat");

        MvcResult first = mockMvc.perform(post("/api/apps/" + runtimeAppKey.appId() + "/chat")
                        .header("X-App-API-Key", runtimeAppKey.plaintextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(post("/api/apps/" + runtimeAppKey.appId() + "/chat")
                        .header("X-App-API-Key", runtimeAppKey.plaintextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void rateLimitsRepeatedAppChatStreamRequests() throws Exception {
        doAnswer(invocation -> {
            java.util.function.Consumer<String> onChunk = invocation.getArgument(3);
            onChunk.accept("hello");
            return null;
        }).when(aiModelService).stream(anyString(), anyList(), anyString(), any());
        RuntimeAppKey runtimeAppKey = runtimeApiKeyForPublishedChatApp("Rate Limited Stream");

        MvcResult first = mockMvc.perform(post("/api/apps/" + runtimeAppKey.appId() + "/chat/stream")
                        .header("X-App-API-Key", runtimeAppKey.plaintextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(post("/api/apps/" + runtimeAppKey.appId() + "/chat/stream")
                        .header("X-App-API-Key", runtimeAppKey.plaintextKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void rateLimitsRepeatedAppRunRequests() throws Exception {
        String appId = publishedWorkflowAppId();
        String plaintextKey = appApiKeyService.create(appId, new CreateApiKeyRequest("runtime")).plaintextKey();

        MvcResult first = mockMvc.perform(post("/api/apps/" + appId + "/run")
                        .header("X-App-API-Key", plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"message\":\"hi\"}}"))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        mockMvc.perform(post("/api/apps/" + appId + "/run")
                        .header("X-App-API-Key", plaintextKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{\"message\":\"hi\"}}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void differentRuntimeKeysDoNotShareSameRateLimitBucket() throws Exception {
        when(aiModelService.generate(anyString(), anyList(), anyString(), any()))
                .thenReturn(AiModelResult.ok("hello from app", null));
        AppResponse app = appService.publish(appService.create(
                new CreateAppRequest("Per Key Bucket", null, AppType.CHAT,
                        new AppConfig("You are support", null, true, null), null, null)).appId());
        String keyA = appApiKeyService.create(app.appId(), new CreateApiKeyRequest("runtime-a")).plaintextKey();
        String keyB = appApiKeyService.create(app.appId(), new CreateApiKeyRequest("runtime-b")).plaintextKey();

        mockMvc.perform(post("/api/apps/" + app.appId() + "/chat")
                        .header("X-App-API-Key", keyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/apps/" + app.appId() + "/chat")
                        .header("X-App-API-Key", keyB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());
    }

    private RuntimeAppKey runtimeApiKeyForPublishedChatApp(String name) {
        AppResponse app = appService.publish(appService.create(
                new CreateAppRequest(name, null, AppType.CHAT, new AppConfig("You are support", null, true, null),
                        null, null)).appId());
        String plaintextKey = appApiKeyService.create(app.appId(), new CreateApiKeyRequest("runtime")).plaintextKey();
        return new RuntimeAppKey(app.appId(), plaintextKey);
    }

    private String publishedWorkflowAppId() {
        WorkflowDefinitionResponse saved = workflowDefinitionService.save(
                new WorkflowDefinitionSaveRequest("Rate Limit Flow", null, simpleWorkflow()));
        workflowDefinitionService.publish(saved.definitionId());
        AppResponse app = appService.create(new CreateAppRequest("Rate Limit Workflow", null, AppType.WORKFLOW, null,
                saved.definitionId(), null));
        return appService.publish(app.appId()).appId();
    }

    private WorkflowDefinition simpleWorkflow() {
        return new WorkflowDefinition(
                List.of(new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
    }

    private record RuntimeAppKey(String appId, String plaintextKey) {
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
