package com.example.agentdemo.demo;

import com.example.agentdemo.workflow.WorkflowCompiler;
import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowDefinitionResponse;
import com.example.agentdemo.workflow.WorkflowDefinitionSaveRequest;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import com.example.agentdemo.workflow.WorkflowDefinitionStatus;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;
import com.example.agentdemo.workflow.WorkflowNodeSchemaRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoWorkflowTemplateTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void customerServiceWorkflowUsesStructuredIntentContract() throws JsonProcessingException {
        WorkflowDefinitionSaveRequest request = DemoWorkflowTemplate.customerServiceWorkflowRequest();
        WorkflowDefinition definition = request.workflowDefinition();

        new WorkflowCompiler(new WorkflowNodeSchemaRegistry()).compile(definition);

        WorkflowNode intentNode = node(definition, "llm_intent");
        assertThat(intentNode.config()).containsEntry("outputMode", "json");
        assertThat(intentNode.config()).containsKey("outputSchema");
        assertThat(schema(intentNode).get("required"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains("intent", "hasOrderId", "needsOrderId", "confidence");
        assertThat(schema(intentNode).get("properties")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsKeys("intent", "orderIds", "needsOrderId", "hasOrderId", "confidence");

        for (String nodeId : List.of("condition_is_order_policy", "condition_is_product",
                "condition_is_order_query", "condition_needs_order_id")) {
            assertThat(node(definition, nodeId).config())
                    .containsEntry("left", "{{nodes.llm_intent.parsed.intent}}")
                    .containsEntry("operator", "equals");
        }

        String json = OBJECT_MAPPER.writeValueAsString(definition);
        assertThat(json)
                .contains("{{nodes.llm_intent.parsed.intent}}")
                .doesNotContain("{{nodes.llm_intent.answer}}", "condition_order_lookup_ready",
                        "input.orderLookupReady", "input.orderToolCalls");
    }

    @Test
    void seederUpdatesLegacySavedCustomerServiceWorkflow() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse legacy = new WorkflowDefinitionResponse(
                "wf-demo",
                DemoWorkflowTemplate.CUSTOMER_SERVICE_WORKFLOW_NAME,
                "legacy",
                legacyCustomerServiceWorkflow(),
                5,
                WorkflowDefinitionStatus.PUBLISHED,
                Instant.EPOCH,
                Instant.EPOCH);
        when(definitionService.list()).thenReturn(List.of(legacy));
        when(definitionService.update(eq("wf-demo"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> new WorkflowDefinitionResponse(
                        "wf-demo",
                        invocation.getArgument(1, WorkflowDefinitionSaveRequest.class).name(),
                        invocation.getArgument(1, WorkflowDefinitionSaveRequest.class).description(),
                        invocation.getArgument(1, WorkflowDefinitionSaveRequest.class).workflowDefinition(),
                        6,
                        WorkflowDefinitionStatus.DRAFT,
                        Instant.EPOCH,
                        Instant.EPOCH));

        new DemoWorkflowSeeder(definitionService).run(null);

        ArgumentCaptor<WorkflowDefinitionSaveRequest> requestCaptor =
                ArgumentCaptor.forClass(WorkflowDefinitionSaveRequest.class);
        verify(definitionService).update(eq("wf-demo"), requestCaptor.capture());
        verify(definitionService).publish("wf-demo");
        assertThat(requestCaptor.getValue().workflowDefinition().nodes())
                .extracting(WorkflowNode::id)
                .contains("llm_intent", "condition_is_order_query")
                .doesNotContain("condition_order_lookup_ready");
        assertThat(node(requestCaptor.getValue().workflowDefinition(), "condition_is_order_query").config())
                .containsEntry("left", "{{nodes.llm_intent.parsed.intent}}")
                .containsEntry("right", "order_query");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(WorkflowNode node) {
        return (Map<String, Object>) node.config().get("outputSchema");
    }

    private static WorkflowNode node(WorkflowDefinition definition, String id) {
        return definition.nodes().stream()
                .filter(node -> id.equals(node.id()))
                .findFirst()
                .orElseThrow();
    }

    private static WorkflowDefinition legacyCustomerServiceWorkflow() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm_intent", "llm", Map.of(
                                "prompt", "请只输出 product_consult 或 order_query")),
                        new WorkflowNode("condition_is_product", "condition", Map.of(
                                "left", "{{nodes.llm_intent.answer}}",
                                "operator", "contains",
                                "right", "product_consult")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "llm_intent"),
                        new WorkflowEdge("llm_intent", "condition_is_product"),
                        new WorkflowEdge("condition_is_product", "end", "true"),
                        new WorkflowEdge("condition_is_product", "end", "false")));
    }
}
