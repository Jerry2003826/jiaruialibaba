package com.example.agentdemo.workflow;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.RunStepRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.workflow.runtime=simple",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:workflow_run_events_owner_isolation;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class WorkflowRunEventsOwnerIsolationTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowDefinitionService workflowDefinitionService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private RunRepository runRepository;

    @Autowired
    private RunStepRepository runStepRepository;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void workflowRunEventsUseJwtOwnerInBackgroundThread() throws Exception {
        String owner = "workflow-owner-a";
        runAs(owner);
        WorkflowDefinitionResponse definition = workflowDefinitionService.save(
                new WorkflowDefinitionSaveRequest("Owner Flow", null, simpleWorkflow()));
        workflowDefinitionService.publish(definition.definitionId());
        WorkflowRunResponse run = workflowService.run(new WorkflowRunRequest(null, definition.definitionId(), null,
                Map.of("message", "hi")));
        SecurityContextHolder.clearContext();

        String body = eventsBody(run.runId(), owner);

        assertThat(body).contains("run_done").contains(run.runId());
        RunEntity entity = runRepository.findByRunIdAndOwnerId(run.runId(), owner).orElseThrow();
        List<RunStepEntity> steps = runStepRepository.findByOwnerIdAndRunIdOrderByStartedAtAsc(owner, run.runId());
        assertThat(entity.getOwnerId()).isEqualTo(owner);
        assertThat(steps).isNotEmpty();
        assertThat(steps).allSatisfy(step -> assertThat(step.getOwnerId()).isEqualTo(owner));
        assertThat(runRepository.existsByRunIdAndOwnerId(run.runId(), "workbench-dev")).isFalse();
        assertThat(runStepRepository.findByOwnerIdAndRunIdOrderByStartedAtAsc("workbench-dev", run.runId()))
                .isEmpty();

        String otherOwnerBody = eventsBody(run.runId(), "workflow-owner-b");
        assertThat(otherOwnerBody).contains("error").doesNotContain("run_done");
    }

    private String eventsBody(String runId, String owner) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/workflows/runs/" + runId + "/events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner, List.of("workflow.read")))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult dispatched = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andReturn();
        return dispatched.getResponse().getContentAsString();
    }

    private WorkflowDefinition simpleWorkflow() {
        return new WorkflowDefinition(
                List.of(new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
    }

    private void runAs(String ownerId) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(ownerId, "n/a", List.of());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
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

}
