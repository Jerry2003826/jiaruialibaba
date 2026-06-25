package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.support.TestAlibabaPolicies;
import com.example.agentdemo.support.WorkflowRuntimeTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Verifies the run-level execution budget against the real runtimes: a run within budget completes
 * and every node execution (including inline loop-body iterations) is charged, while a run that
 * exceeds the budget is aborted with {@code WORKFLOW_BUDGET_EXCEEDED}.
 */
class WorkflowRunBudgetTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name("budget-test-", 0)
            .factory());

    @AfterEach
    void shutdownExecutorService() {
        executorService.shutdownNow();
    }

    @Test
    void simpleRuntimeCompletesAndChargesEveryStepWhenWithinBudget() {
        WorkflowRuntimeTestSupport.RuntimeStack stack = simpleStack();
        stack.budgetRegistry().open("run-ok", 100, 0);
        try {
            WorkflowRuntime.WorkflowExecutionResult result =
                    stack.runtime().run("run-ok", compiler.compile(loopDefinition(2)), Map.of());

            assertThat(result.steps()).extracting(WorkflowStepSummary::nodeId).contains("decrement");
            // start + loop_1 + decrement x2 + after_loop + end: loop-body iterations are charged too.
            assertThat(stack.budgetRegistry().stepsConsumed("run-ok")).isEqualTo(6);
        }
        finally {
            stack.budgetRegistry().close("run-ok");
        }
    }

    @Test
    void simpleRuntimeAbortsWhenStepBudgetExceededInsideLoopBody() {
        WorkflowRuntimeTestSupport.RuntimeStack stack = simpleStack();
        stack.budgetRegistry().open("run-budget", 2, 0);
        try {
            assertThatThrownBy(() ->
                    stack.runtime().run("run-budget", compiler.compile(loopDefinition(5)), Map.of()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_BUDGET_EXCEEDED"));
        }
        finally {
            stack.budgetRegistry().close("run-budget");
        }
    }

    @Test
    void graphRuntimeAbortsWhenStepBudgetExceeded() {
        WorkflowRuntimeTestSupport.GraphRuntimeStack stack = graphStack();
        stack.budgetRegistry().open("run-budget-graph", 2, 0);
        try {
            assertThatThrownBy(() ->
                    stack.runtime().run("run-budget-graph", compiler.compile(loopDefinition(5)), Map.of()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_BUDGET_EXCEEDED"));
        }
        finally {
            stack.budgetRegistry().close("run-budget-graph");
        }
    }

    private WorkflowRuntimeTestSupport.RuntimeStack simpleStack() {
        return WorkflowRuntimeTestSupport.simpleRuntimeStack(
                mock(WorkflowDefinitionService.class),
                mock(RagService.class),
                mock(AiModelService.class),
                WorkflowRuntimeTestSupport.localToolGateway(),
                TestAlibabaPolicies.legacyFallbackAllowed(),
                WorkflowRuntimeTestSupport.mockPermissiveTraceService(),
                executorService);
    }

    private WorkflowRuntimeTestSupport.GraphRuntimeStack graphStack() {
        return WorkflowRuntimeTestSupport.graphRuntimeStack(
                mock(WorkflowDefinitionService.class),
                mock(RagService.class),
                mock(AiModelService.class),
                WorkflowRuntimeTestSupport.localToolGateway(),
                TestAlibabaPolicies.legacyFallbackAllowed(),
                WorkflowRuntimeTestSupport.mockPermissiveTraceService(),
                executorService);
    }

    private WorkflowDefinition loopDefinition(int maxIterations) {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("loop_1", "loop", Map.of(
                                "maxIterations", maxIterations,
                                "left", "1",
                                "operator", "equals",
                                "right", "1")),
                        new WorkflowNode("decrement", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("after_loop", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "loop_1"),
                        new WorkflowEdge("loop_1", "decrement", "body"),
                        new WorkflowEdge("loop_1", "after_loop", "exit"),
                        new WorkflowEdge("decrement", "loop_back"),
                        new WorkflowEdge("loop_back", "loop_1"),
                        new WorkflowEdge("after_loop", "end")));
    }

}
