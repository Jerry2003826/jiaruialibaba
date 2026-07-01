package com.example.agentdemo.app;

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

    private String appIdOf(String responseBody) {
        int idx = responseBody.indexOf("\"appId\":\"");
        int start = idx + "\"appId\":\"".length();
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
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
