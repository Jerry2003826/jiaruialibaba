package com.example.agentdemo.app.apikey;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0-4: runtime API keys authenticate an app's runtime endpoints only. Verifies create (plaintext
 * shown once), list (no plaintext), both header styles, revoke, cross-app rejection, and that a key
 * cannot reach the console management API.
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_apikey_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class AppApiKeyIntegrationTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AiModelService aiModelService;

    @Test
    void apiKeyCanCallOwnAppRuntimeAndIsRevocable() throws Exception {
        Mockito.when(aiModelService.generate(Mockito.anyString(), Mockito.anyList(), Mockito.anyString(),
                Mockito.any())).thenReturn(AiModelResult.ok("hello from app", null));
        String appId = publishedChatApp("Key App");

        // Create key: plaintext returned once.
        JsonNode created = postJson("/api/apps/" + appId + "/api-keys", List.of("app.write"),
                "{\"name\":\"prod\"}");
        String plaintext = created.path("data").path("plaintextKey").asText();
        String keyId = created.path("data").path("keyId").asText();
        assertThat(plaintext).startsWith("app_");
        assertThat(keyId).startsWith("ak_");

        // List: no plaintext leaks.
        String listBody = mockMvc.perform(get("/api/apps/" + appId + "/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.read"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).contains(keyId).doesNotContain(plaintext);

        // Runtime call via X-App-API-Key header.
        mockMvc.perform(post("/api/apps/" + appId + "/chat")
                        .header(AppApiKeyAuthenticationFilter.API_KEY_HEADER, plaintext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        // Runtime call via Authorization: Bearer app_...
        mockMvc.perform(post("/api/apps/" + appId + "/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + plaintext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        // A key cannot reach the console management API (list its own app's keys).
        mockMvc.perform(get("/api/apps/" + appId + "/api-keys")
                        .header(AppApiKeyAuthenticationFilter.API_KEY_HEADER, plaintext))
                .andExpect(status().isForbidden());

        // Revoke, then the key no longer authenticates.
        mockMvc.perform(delete("/api/apps/" + appId + "/api-keys/" + keyId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.write"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/apps/" + appId + "/chat")
                        .header(AppApiKeyAuthenticationFilter.API_KEY_HEADER, plaintext)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void keyForOneAppCannotCallAnotherApp() throws Exception {
        Mockito.when(aiModelService.generate(Mockito.anyString(), Mockito.anyList(), Mockito.anyString(),
                Mockito.any())).thenReturn(AiModelResult.ok("ok", null));
        String appA = publishedChatApp("App A");
        String appB = publishedChatApp("App B");

        JsonNode created = postJson("/api/apps/" + appA + "/api-keys", List.of("app.write"), "{}");
        String keyA = created.path("data").path("plaintextKey").asText();

        mockMvc.perform(post("/api/apps/" + appB + "/chat")
                        .header(AppApiKeyAuthenticationFilter.API_KEY_HEADER, keyA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isForbidden());
    }

    private String publishedChatApp(String name) throws Exception {
        JsonNode created = postJson("/api/apps", List.of("app.write"),
                "{\"name\":\"" + name + "\",\"type\":\"CHAT\"}");
        String appId = created.path("data").path("appId").asText();
        mockMvc.perform(post("/api/apps/" + appId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("app.write"))))
                .andExpect(status().isOk());
        return appId;
    }

    private JsonNode postJson(String path, List<String> scopes, String body) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header(HttpHeaders.AUTHORIZATION, bearer(scopes))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
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
