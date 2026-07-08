package com.example.agentdemo.demo;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.support.TestAlibabaPolicies;
import com.example.agentdemo.support.WorkflowRuntimeTestSupport;
import com.example.agentdemo.workflow.WorkflowCompiler;
import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowExecutionPlan;
import com.example.agentdemo.workflow.WorkflowNodeSchemaRegistry;
import com.example.agentdemo.workflow.WorkflowRuntime;
import com.example.agentdemo.workflow.WorkflowStepSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DemoWorkflowRuntimeTest {

    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name("demo-workflow-runtime-test-", 0)
            .factory());

    @AfterEach
    void shutdownExecutorService() {
        executorService.shutdownNow();
    }

    @Test
    void travelExpenseDemoRoutesMissingInfoAutoApproveAndManualReview() {
        WorkflowDefinition definition = DemoWorkflowTemplate.travelExpenseConditionWorkflowRequest()
                .workflowDefinition();
        WorkflowExecutionPlan plan = new WorkflowCompiler(new WorkflowNodeSchemaRegistry()).compile(definition);
        WorkflowRuntime runtime = graphRuntime();

        assertThat(nodeIds(runtime.run("run-missing-info", plan, Map.of(
                "message", "上海差旅报销",
                "expenseType", "travel",
                "receiptProvided", false,
                "amount", 380,
                "priority", "normal"))))
                .containsExactly("start", "condition_expense_complete", "tool_missing_info", "end");

        assertThat(nodeIds(runtime.run("run-auto-approve", plan, Map.of(
                "message", "上海差旅报销",
                "expenseType", "travel",
                "receiptProvided", true,
                "amount", 380,
                "priority", "normal"))))
                .containsExactly("start", "condition_expense_complete", "condition_manual_review",
                        "tool_auto_approve", "end");

        assertThat(nodeIds(runtime.run("run-manual-review", plan, Map.of(
                "message", "上海差旅报销，帮我加急",
                "expenseType", "travel",
                "receiptProvided", true,
                "amount", 1200,
                "priority", "normal"))))
                .containsExactly("start", "condition_expense_complete", "condition_manual_review",
                        "tool_manual_review", "end");
    }

    private WorkflowRuntime graphRuntime() {
        return WorkflowRuntimeTestSupport.graphRuntimeStack(
                mock(com.example.agentdemo.workflow.WorkflowDefinitionService.class),
                mock(RagService.class),
                mock(AiModelService.class),
                WorkflowRuntimeTestSupport.localToolGateway(),
                TestAlibabaPolicies.legacyFallbackAllowed(),
                WorkflowRuntimeTestSupport.mockPermissiveTraceService(),
                executorService)
                .runtime();
    }

    private static List<String> nodeIds(WorkflowRuntime.WorkflowExecutionResult result) {
        return result.steps().stream()
                .map(WorkflowStepSummary::nodeId)
                .toList();
    }
}
