package com.example.agentdemo.demo;

import com.example.agentdemo.common.BusinessDataException;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
    @SuppressWarnings("unchecked")
    void travelExpenseWorkflowDemonstratesAllAndAnyCompositeConditions() {
        WorkflowDefinitionSaveRequest request = DemoWorkflowTemplate.travelExpenseConditionWorkflowRequest();
        WorkflowDefinition definition = request.workflowDefinition();

        new WorkflowCompiler(new WorkflowNodeSchemaRegistry()).compile(definition);

        assertThat(request.name()).isEqualTo(DemoWorkflowTemplate.TRAVEL_EXPENSE_CONDITION_WORKFLOW_NAME);
        assertThat(definition.nodes())
                .extracting(WorkflowNode::id)
                .containsExactly("start", "condition_expense_complete", "condition_manual_review",
                        "tool_missing_info", "tool_manual_review", "tool_auto_approve", "end");
        assertThat(List.of("tool_missing_info", "tool_manual_review", "tool_auto_approve"))
                .allSatisfy(nodeId -> {
                    WorkflowNode outcome = node(definition, nodeId);
                    assertThat(outcome.type()).isEqualTo("llm");
                    assertThat(outcome.config()).containsKey("prompt").doesNotContainKey("toolName");
                });

        WorkflowNode complete = node(definition, "condition_expense_complete");
        assertThat(complete.config()).containsEntry("mode", "all");
        List<Map<String, Object>> completeConditions = (List<Map<String, Object>>) complete.config().get("conditions");
        assertThat(completeConditions).hasSize(3);
        assertThat(completeConditions)
                .extracting(condition -> condition.get("left"))
                .containsExactly("{{input.expenseType}}", "{{input.receiptProvided}}", "{{input.amount}}");
        assertThat(completeConditions)
                .extracting(condition -> condition.get("operator"))
                .containsExactly("exists", "equals", "greaterThan");

        WorkflowNode review = node(definition, "condition_manual_review");
        assertThat(review.config()).containsEntry("mode", "any");
        List<Map<String, Object>> reviewConditions = (List<Map<String, Object>>) review.config().get("conditions");
        assertThat(reviewConditions).hasSize(3);
        assertThat(reviewConditions)
                .extracting(condition -> condition.get("left"))
                .containsExactly("{{input.priority}}", "{{input.message}}", "{{input.amount}}");
        assertThat(reviewConditions)
                .extracting(condition -> condition.get("operator"))
                .containsExactly("equals", "contains", "greaterThan");

        assertThat(definition.edges())
                .extracting(WorkflowEdge::condition)
                .contains("true", "false");
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
        WorkflowDefinitionResponse expenseDemo = currentTravelExpenseDemo();
        when(definitionService.list()).thenReturn(List.of(legacy, expenseDemo));
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

    @Test
    void seederCreatesMissingTravelExpenseConditionWorkflow() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse customerService = new WorkflowDefinitionResponse(
                "wf-demo",
                DemoWorkflowTemplate.CUSTOMER_SERVICE_WORKFLOW_NAME,
                "current",
                DemoWorkflowTemplate.customerServiceWorkflowRequest().workflowDefinition(),
                6,
                WorkflowDefinitionStatus.PUBLISHED,
                Instant.EPOCH,
                Instant.EPOCH);
        when(definitionService.list()).thenReturn(List.of(customerService));
        when(definitionService.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> new WorkflowDefinitionResponse(
                        "wf-expense-demo",
                        invocation.getArgument(0, WorkflowDefinitionSaveRequest.class).name(),
                        invocation.getArgument(0, WorkflowDefinitionSaveRequest.class).description(),
                        invocation.getArgument(0, WorkflowDefinitionSaveRequest.class).workflowDefinition(),
                        1,
                        WorkflowDefinitionStatus.DRAFT,
                        Instant.EPOCH,
                        Instant.EPOCH));

        new DemoWorkflowSeeder(definitionService).run(null);

        ArgumentCaptor<WorkflowDefinitionSaveRequest> requestCaptor =
                ArgumentCaptor.forClass(WorkflowDefinitionSaveRequest.class);
        verify(definitionService).save(requestCaptor.capture());
        verify(definitionService).publish("wf-expense-demo");
        assertThat(requestCaptor.getValue().name())
                .isEqualTo(DemoWorkflowTemplate.TRAVEL_EXPENSE_CONDITION_WORKFLOW_NAME);
    }

    @Test
    void seederUpdatesLegacyTravelExpenseConditionWorkflow() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse legacy = new WorkflowDefinitionResponse(
                "wf-expense-demo",
                DemoWorkflowTemplate.TRAVEL_EXPENSE_CONDITION_WORKFLOW_NAME,
                "legacy",
                new WorkflowDefinition(
                        List.of(
                                new WorkflowNode("start", "start", Map.of()),
                                new WorkflowNode("condition_expense_complete", "condition", Map.of(
                                        "left", "{{input.message}}",
                                        "operator", "contains",
                                        "right", "报销")),
                                new WorkflowNode("end", "end", Map.of())),
                        List.of(
                                new WorkflowEdge("start", "condition_expense_complete"),
                                new WorkflowEdge("condition_expense_complete", "end", "true"),
                                new WorkflowEdge("condition_expense_complete", "end", "false"))),
                1,
                WorkflowDefinitionStatus.PUBLISHED,
                Instant.EPOCH,
                Instant.EPOCH);
        WorkflowDefinitionResponse customerService = currentCustomerServiceDemo();
        when(definitionService.list()).thenReturn(List.of(customerService, legacy));
        when(definitionService.update(eq("wf-expense-demo"), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> new WorkflowDefinitionResponse(
                        "wf-expense-demo",
                        invocation.getArgument(1, WorkflowDefinitionSaveRequest.class).name(),
                        invocation.getArgument(1, WorkflowDefinitionSaveRequest.class).description(),
                        invocation.getArgument(1, WorkflowDefinitionSaveRequest.class).workflowDefinition(),
                        2,
                        WorkflowDefinitionStatus.DRAFT,
                        Instant.EPOCH,
                        Instant.EPOCH));

        new DemoWorkflowSeeder(definitionService).run(null);

        ArgumentCaptor<WorkflowDefinitionSaveRequest> requestCaptor =
                ArgumentCaptor.forClass(WorkflowDefinitionSaveRequest.class);
        verify(definitionService).update(eq("wf-expense-demo"), requestCaptor.capture());
        verify(definitionService).publish("wf-expense-demo");
        assertThat(node(requestCaptor.getValue().workflowDefinition(), "condition_expense_complete").config())
                .containsEntry("mode", "all")
                .containsKey("conditions");
        assertThat(node(requestCaptor.getValue().workflowDefinition(), "condition_manual_review").config())
                .containsEntry("mode", "any")
                .containsKey("conditions");
    }

    @Test
    void travelExpenseSyncDetectsLegacyGetCurrentTimeOutcomeNodes() {
        WorkflowDefinition current = DemoWorkflowTemplate.travelExpenseConditionWorkflowRequest()
                .workflowDefinition();
        List<String> outcomeIds = List.of("tool_missing_info", "tool_manual_review", "tool_auto_approve");
        List<WorkflowNode> legacyNodes = current.nodes().stream()
                .map(node -> outcomeIds.contains(node.id())
                        ? new WorkflowNode(node.id(), "tool", Map.of(
                                "toolName", "getCurrentTime",
                                "arguments", Map.of("message", "legacy outcome")), node.label(), node.route())
                        : node)
                .toList();

        assertThat(DemoWorkflowTemplate.travelExpenseConditionWorkflowNeedsSync(
                new WorkflowDefinition(legacyNodes, current.edges())))
                .isTrue();
    }

    @Test
    void seederLeavesCurrentDemoWorkflowsUntouched() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse customerService = new WorkflowDefinitionResponse(
                "wf-demo",
                DemoWorkflowTemplate.CUSTOMER_SERVICE_WORKFLOW_NAME,
                "current",
                DemoWorkflowTemplate.customerServiceWorkflowRequest().workflowDefinition(),
                6,
                WorkflowDefinitionStatus.PUBLISHED,
                Instant.EPOCH,
                Instant.EPOCH);
        WorkflowDefinitionResponse expenseDemo = new WorkflowDefinitionResponse(
                "wf-expense-demo",
                DemoWorkflowTemplate.TRAVEL_EXPENSE_CONDITION_WORKFLOW_NAME,
                "current",
                DemoWorkflowTemplate.travelExpenseConditionWorkflowRequest().workflowDefinition(),
                1,
                WorkflowDefinitionStatus.PUBLISHED,
                Instant.EPOCH,
                Instant.EPOCH);
        when(definitionService.list()).thenReturn(List.of(customerService, expenseDemo));

        new DemoWorkflowSeeder(definitionService).run(null);

        verify(definitionService, times(2)).list();
        verifyNoMoreInteractions(definitionService);
    }

    @Test
    void seederDoesNotAbortStartupWhenGovernanceBlocksDemoPublication() {
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
        when(definitionService.list()).thenReturn(List.of(legacy, currentTravelExpenseDemo()));
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
        doThrow(new BusinessDataException(
                "WORKFLOW_GOVERNANCE_BLOCKED", "governance blocked demo publication", null))
                .when(definitionService).publish("wf-demo");

        assertThatCode(() -> new DemoWorkflowSeeder(definitionService).run(null))
                .doesNotThrowAnyException();

        verify(definitionService).update(eq("wf-demo"), org.mockito.ArgumentMatchers.any());
        verify(definitionService).publish("wf-demo");
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

    private static WorkflowDefinitionResponse currentCustomerServiceDemo() {
        return new WorkflowDefinitionResponse(
                "wf-demo",
                DemoWorkflowTemplate.CUSTOMER_SERVICE_WORKFLOW_NAME,
                "current",
                DemoWorkflowTemplate.customerServiceWorkflowRequest().workflowDefinition(),
                6,
                WorkflowDefinitionStatus.PUBLISHED,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private static WorkflowDefinitionResponse currentTravelExpenseDemo() {
        return new WorkflowDefinitionResponse(
                "wf-expense-demo",
                DemoWorkflowTemplate.TRAVEL_EXPENSE_CONDITION_WORKFLOW_NAME,
                "current",
                DemoWorkflowTemplate.travelExpenseConditionWorkflowRequest().workflowDefinition(),
                1,
                WorkflowDefinitionStatus.PUBLISHED,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
