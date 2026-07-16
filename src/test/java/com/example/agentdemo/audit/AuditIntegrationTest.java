package com.example.agentdemo.audit;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.common.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the P0-5 audit trail: the {@code @Audited} aspect writes success and failure rows for
 * management actions, and the read endpoint is guarded by {@code SCOPE_audit.read}.
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "demo.workflow.require-published-for-run=false",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_audit_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class AuditIntegrationTest {

    private static final String TEST_SECRET = "test-security-secret-32-bytes-minimum-value";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowDefinitionService workflowDefinitionService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void publishWritesSuccessAuditRow() {
        WorkflowDefinitionResponse saved = workflowDefinitionService.save(
                new WorkflowDefinitionSaveRequest("Audit Flow", "for audit", validDefinition()));

        workflowDefinitionService.publish(saved.definitionId());

        assertThat(auditLogRepository.findAll())
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo("workflow.publish");
                    assertThat(entry.getResourceType()).isEqualTo("workflow");
                    assertThat(entry.getResourceId()).isEqualTo(saved.definitionId());
                    assertThat(entry.isSuccess()).isTrue();
                });
    }

    @Test
    void failedPublishWritesFailureAuditRow() {
        assertThatThrownBy(() -> workflowDefinitionService.publish("does-not-exist"))
                .isInstanceOf(BusinessException.class);

        assertThat(auditLogRepository.findAll())
                .anySatisfy(entry -> {
                    assertThat(entry.getAction()).isEqualTo("workflow.publish");
                    assertThat(entry.getResourceId()).isEqualTo("does-not-exist");
                    assertThat(entry.isSuccess()).isFalse();
                    assertThat(entry.getErrorCode()).isEqualTo("WORKFLOW_DEFINITION_NOT_FOUND");
                });
    }

    @Test
    void auditLogEndpointRequiresAuditReadScope() throws Exception {
        mockMvc.perform(get("/api/audit-logs"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("chat.execute"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("audit.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void publishOverHttpAppearsInOwnerScopedAuditLog() throws Exception {
        String createBody = mockMvc.perform(post("/api/workflows/definitions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("workflow.edit")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Http Audit Flow",
                                  "description": "http",
                                  "workflowDefinition": {
                                    "nodes": [
                                      {"id": "start", "type": "start", "config": {}},
                                      {"id": "end", "type": "end", "config": {}}
                                    ],
                                    "edges": [ {"from": "start", "to": "end"} ]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String definitionId = definitionIdOf(createBody);

        mockMvc.perform(post("/api/workflows/definitions/" + definitionId + "/publish")
                        .header(HttpHeaders.AUTHORIZATION,
                                bearer(List.of("workflow.publish", "workflow.run"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/audit-logs?resourceType=workflow")
                        .header(HttpHeaders.AUTHORIZATION, bearer(List.of("audit.read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[?(@.action=='workflow.publish' && @.resourceId=='"
                        + definitionId + "')].actorType").value(org.hamcrest.Matchers.hasItem("CONSOLE_JWT")));
    }

    private String definitionIdOf(String responseBody) {
        int idx = responseBody.indexOf("\"definitionId\":\"");
        int start = idx + "\"definitionId\":\"".length();
        int end = responseBody.indexOf('"', start);
        return responseBody.substring(start, end);
    }

    private WorkflowDefinition validDefinition() {
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
