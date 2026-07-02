package com.example.agentdemo.app;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.RunStepRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "demo.rag.retriever=keyword",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:app_stream_owner_isolation;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key="
})
@AutoConfigureMockMvc
class AppStreamOwnerIsolationTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RunStepRepository runStepRepository;

    @MockBean
    private AiModelService aiModelService;

    @Test
    void chatStreamCreatesRunAndStepForJwtOwner() throws Exception {
        doAnswer(invocation -> {
            java.util.function.Consumer<String> onChunk = invocation.getArgument(3);
            onChunk.accept("hello");
            return null;
        }).when(aiModelService).stream(anyString(), anyList(), anyString(), any());
        String owner = "stream-owner-a";
        String appId = createAndPublishChatApp(owner);

        mockMvc.perform(post("/api/apps/" + appId + "/chat/stream")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, List.of("app.run")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk());

        RunEntity run = waitForRun(appId, owner);
        List<RunStepEntity> steps = runStepRepository.findByOwnerIdAndRunIdOrderByStartedAtAsc(owner,
                run.getRunId());

        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(run.getOwnerId()).isEqualTo(owner);
        assertThat(steps).isNotEmpty();
        assertThat(steps).allSatisfy(step -> assertThat(step.getOwnerId()).isEqualTo(owner));
        assertThat(runRepository.existsByOwnerIdAndAppId("workbench-dev", appId)).isFalse();
        assertThat(runStepRepository.findByOwnerIdAndRunIdOrderByStartedAtAsc("workbench-dev", run.getRunId()))
                .isEmpty();
        Mockito.verify(aiModelService).stream(anyString(), anyList(), anyString(), any());
    }

    private RunEntity waitForRun(String appId, String owner) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (Instant.now().isBefore(deadline)) {
            List<RunEntity> runs = runRepository.findAll().stream()
                    .filter(run -> appId.equals(run.getAppId()) && owner.equals(run.getOwnerId()))
                    .toList();
            if (!runs.isEmpty()) {
                RunEntity run = runs.getFirst();
                boolean hasStep = !runStepRepository.findByOwnerIdAndRunIdOrderByStartedAtAsc(owner,
                        run.getRunId()).isEmpty();
                if (run.getStatus() == RunStatus.SUCCEEDED && hasStep) {
                    return run;
                }
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("Timed out waiting for succeeded run owned by " + owner);
    }

    private String bearer(String subject, List<String> scopes) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer("agent-backend-demo")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("scope", String.join(" ", scopes))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return "Bearer " + jwt.serialize();
    }

    private String createAndPublishChatApp(String owner) throws Exception {
        String createBody = mockMvc.perform(post("/api/apps")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, List.of("app.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Stream Chat\",\"type\":\"CHAT\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String appId = appIdOf(createBody);
        mockMvc.perform(post("/api/apps/" + appId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, List.of("app.write"))))
                .andExpect(status().isOk());
        return appId;
    }

    private String appIdOf(String responseBody) {
        int idx = responseBody.indexOf("\"appId\":\"");
        int start = idx + "\"appId\":\"".length();
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
    }

}
