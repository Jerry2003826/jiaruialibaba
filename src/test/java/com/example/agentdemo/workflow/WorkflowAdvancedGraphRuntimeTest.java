package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.support.WorkflowRuntimeTestSupport;
import com.example.agentdemo.trace.TraceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowAdvancedGraphRuntimeTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name("advanced-graph-test-", 0)
            .factory());

    @AfterEach
    void shutdownExecutorService() {
        executorService.shutdownNow();
    }

    @Test
    void graphRuntimeRunsLoopWithInlineBodyTrace() {
        WorkflowRuntimeTestSupport.GraphRuntimeStack stack = graphStack();
        WorkflowExecutionPlan plan = compiler.compile(fixedLoopDefinition(2));

        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("graph-loop", plan, Map.of());

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("start", "loop_1", "decrement", "after_loop", "end");
        assertThat(result.steps().stream().filter(step -> "decrement".equals(step.nodeId()))).hasSize(2);
    }

    @Test
    void graphRuntimeRunsDynamicToolSequence() {
        WorkflowRuntimeTestSupport.GraphRuntimeStack stack = graphStack();
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("dyn_1", "dynamic", Map.of(
                                "itemsFrom", "{{input.tools}}",
                                "allowedTools", List.of("getCurrentTime"))),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "dyn_1"),
                        new WorkflowEdge("dyn_1", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("graph-dynamic", plan, Map.of(
                "tools", List.of(
                        Map.of("toolName", "getCurrentTime"),
                        Map.of("toolName", "getCurrentTime"))));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("dyn_1", "dyn_1:dynamic:0:getCurrentTime", "dyn_1:dynamic:1:getCurrentTime");
        WorkflowStepSummary dynamicStep = result.steps().stream()
                .filter(step -> "dyn_1".equals(step.nodeId()))
                .findFirst()
                .orElseThrow();
        assertThat(((Map<?, ?>) dynamicStep.output()).get("itemCount")).isEqualTo(2);
    }

    @Test
    void graphRuntimeRunsSubgraphThenLoop() {
        WorkflowDefinition childDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_1", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_1"),
                        new WorkflowEdge("tool_1", "end")));

        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        org.mockito.Mockito.when(definitionService.resolveDefinition("child-wf", null))
                .thenReturn(new WorkflowDefinitionResolution("child-wf", 1, childDefinition));

        WorkflowRuntimeTestSupport.GraphRuntimeStack stack = WorkflowRuntimeTestSupport.graphRuntimeStack(
                definitionService,
                mock(RagService.class),
                mock(AiModelService.class),
                WorkflowRuntimeTestSupport.localToolGateway(),
                com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(),
                WorkflowRuntimeTestSupport.mockPermissiveTraceService(),
                executorService);

        WorkflowExecutionPlan plan = compiler.compile(subgraphThenLoopDefinition());
        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("graph-nested", plan,
                Map.of("message", "hello"));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("sub_1", "loop_1", "body_tool", "after_loop", "end");
    }

    private WorkflowRuntimeTestSupport.GraphRuntimeStack graphStack() {
        TraceService traceService = WorkflowRuntimeTestSupport.mockPermissiveTraceService();
        return WorkflowRuntimeTestSupport.graphRuntimeStack(
                mock(WorkflowDefinitionService.class),
                mock(RagService.class),
                mock(AiModelService.class),
                WorkflowRuntimeTestSupport.localToolGateway(),
                com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(),
                traceService,
                executorService);
    }

    private WorkflowDefinition fixedLoopDefinition(int maxIterations) {
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

    private WorkflowDefinition subgraphThenLoopDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("sub_1", "subgraph", Map.of("definitionId", "child-wf")),
                        new WorkflowNode("loop_1", "loop", Map.of(
                                "maxIterations", 1,
                                "left", "{{input.message}}",
                                "operator", "exists")),
                        new WorkflowNode("body_tool", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("after_loop", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "sub_1"),
                        new WorkflowEdge("sub_1", "loop_1"),
                        new WorkflowEdge("loop_1", "body_tool", "body"),
                        new WorkflowEdge("loop_1", "after_loop", "exit"),
                        new WorkflowEdge("body_tool", "loop_back"),
                        new WorkflowEdge("loop_back", "loop_1"),
                        new WorkflowEdge("after_loop", "end")));
    }

}
