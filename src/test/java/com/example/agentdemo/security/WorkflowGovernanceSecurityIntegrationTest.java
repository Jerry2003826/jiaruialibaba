package com.example.agentdemo.security;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.workflow.WorkflowGenerationService;
import com.example.agentdemo.workflow.WorkflowGenerationStatus;
import com.example.agentdemo.workflow.WorkflowDefinitionResponse;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import com.example.agentdemo.workflow.WorkflowDefinitionStatus;
import com.example.agentdemo.workflow.WorkflowGovernanceEvaluationRequest;
import com.example.agentdemo.workflow.WorkflowGovernanceEvaluationResponse;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:workflow_governance_security_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword",
        "demo.security.rate-limit.enabled=false"
})
@AutoConfigureMockMvc
class WorkflowGovernanceSecurityIntegrationTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";
    private static final String EVALUATION_REQUEST = """
            {
              "workflowDefinition": {
                "nodes": [
                  {"id": "start", "type": "start", "config": {}},
                  {"id": "end", "type": "end", "config": {}}
                ],
                "edges": [{"from": "start", "to": "end"}]
              }
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WorkflowGenerationService workflowGenerationService;

    @MockBean
    private WorkflowDefinitionService workflowDefinitionService;

    @Test
    void editOnlyScopeCannotEvaluateWorkflowGovernance() throws Exception {
        mockMvc.perform(post("/api/workflows/governance/evaluate")
                        .header(HttpHeaders.AUTHORIZATION, bearer("edit-only", List.of("workflow.edit")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVALUATION_REQUEST))
                .andExpect(status().isForbidden());

        verifyNoInteractions(workflowGenerationService);
    }

    @Test
    void runScopeCanEnterWorkflowGovernanceController() throws Exception {
        when(workflowGenerationService.evaluateGovernance(any(WorkflowGovernanceEvaluationRequest.class)))
                .thenReturn(new WorkflowGovernanceEvaluationResponse(
                        WorkflowGenerationStatus.READY, null, null, null, null, null));

        mockMvc.perform(post("/api/workflows/governance/evaluate")
                        .header(HttpHeaders.AUTHORIZATION, bearer("runner", List.of("workflow.run")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVALUATION_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("READY"));

        verify(workflowGenerationService).evaluateGovernance(any(WorkflowGovernanceEvaluationRequest.class));
    }

    @Test
    void publishOnlyScopeCannotEnterWorkflowPublishController() throws Exception {
        mockMvc.perform(post("/api/workflows/definitions/wf-1/publish")
                        .header(HttpHeaders.AUTHORIZATION, bearer("publisher-only", List.of("workflow.publish"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(workflowDefinitionService);
    }

    @Test
    void publishAndRunScopesCanEnterWorkflowPublishController() throws Exception {
        Instant now = Instant.now();
        when(workflowDefinitionService.publish("wf-1"))
                .thenReturn(new WorkflowDefinitionResponse(
                        "wf-1", "Workflow", null, null, 1, WorkflowDefinitionStatus.PUBLISHED, now, now));

        mockMvc.perform(post("/api/workflows/definitions/wf-1/publish")
                        .header(HttpHeaders.AUTHORIZATION,
                                bearer("publisher-runner", List.of("workflow.publish", "workflow.run"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.definitionId").value("wf-1"));

        verify(workflowDefinitionService).publish("wf-1");
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
