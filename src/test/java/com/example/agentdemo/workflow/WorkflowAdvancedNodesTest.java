package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowAdvancedNodesTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name("advanced-workflow-test-", 0)
            .factory());

    @AfterEach
    void shutdownExecutorService() {
        executorService.shutdownNow();
    }

    @Test
    void compilesLoopWorkflowWithLoopBackCycle() {
        WorkflowExecutionPlan plan = compiler.compile(loopDefinition());

        assertThat(plan.loopBlocks()).hasSize(1);
        assertThat(plan.loopBlocks().getFirst().bodyNodeIds()).containsExactly("decrement");
        assertThat(plan.isCompositeScopedNode("decrement")).isTrue();
        assertThat(plan.isCompositeScopedNode("loop_back")).isTrue();
        assertThat(plan.isCompositeContainerNode("loop_1")).isTrue();
    }

    @Test
    void compilesDynamicAndSubgraphNodeTypes() {
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("sub_1", "subgraph", Map.of("definitionId", "wf-child")),
                        new WorkflowNode("dyn_1", "dynamic", Map.of("itemsFrom", "{{input.tools}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "sub_1"),
                        new WorkflowEdge("sub_1", "dyn_1"),
                        new WorkflowEdge("dyn_1", "end"))));

        assertThat(plan.nodesById()).containsKeys("sub_1", "dyn_1");
        assertThat(plan.isCompositeContainerNode("sub_1")).isTrue();
        assertThat(plan.isCompositeContainerNode("dyn_1")).isTrue();
    }

    @Test
    void rejectsDynamicNodeInsideParallelBranch() {
        WorkflowDefinition definition = parallelWithBranchNode("dyn_1", "dynamic",
                Map.of("itemsFrom", "{{input.tools}}"));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Parallel branches only support linear nodes");
    }

    @Test
    void rejectsSubgraphNodeInsideParallelBranch() {
        WorkflowDefinition definition = parallelWithBranchNode("sub_1", "subgraph",
                Map.of("definitionId", "child-wf"));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Parallel branches only support linear nodes");
    }

    @Test
    void runsLoopWithInlineBodyTrace() {
        WorkflowRuntimeTestSupport.RuntimeStack stack = simpleStack();
        WorkflowExecutionPlan plan = compiler.compile(fixedLoopDefinition(2));

        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("run-loop", plan, Map.of("message", "hi"));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("start", "loop_1", "decrement", "after_loop", "end");
        assertThat(result.steps().stream().filter(step -> "decrement".equals(step.nodeId()))).hasSize(2);
        assertLoopIterations(result, 2);
    }

    @Test
    void runsLoopWithTimeoutAndInlineBodyTrace() {
        WorkflowRuntimeTestSupport.RuntimeStack stack = simpleStack();
        WorkflowExecutionPlan plan = compiler.compile(fixedLoopDefinition(2, Map.of("timeoutMs", 5_000)));

        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("run-loop-timeout", plan,
                Map.of("message", "hi"));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("loop_1", "decrement", "after_loop", "end");
        assertLoopIterations(result, 2);
    }

    @Test
    void loopSkipsBodyWhenConditionIsFalseInitially() {
        WorkflowRuntimeTestSupport.RuntimeStack stack = simpleStack();
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("loop_1", "loop", Map.of(
                                "maxIterations", 5,
                                "left", "no-match",
                                "operator", "equals",
                                "right", "yes")),
                        new WorkflowNode("body", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("after_loop", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "loop_1"),
                        new WorkflowEdge("loop_1", "body", "body"),
                        new WorkflowEdge("loop_1", "after_loop", "exit"),
                        new WorkflowEdge("body", "loop_back"),
                        new WorkflowEdge("loop_back", "loop_1"),
                        new WorkflowEdge("after_loop", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("run-loop-skip", plan, Map.of());

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .doesNotContain("body");
        assertLoopIterations(result, 0);
    }

    @Test
    void loopStopsAtMaxIterationsEvenWhenConditionRemainsTrue() {
        WorkflowRuntimeTestSupport.RuntimeStack stack = simpleStack();
        WorkflowExecutionPlan plan = compiler.compile(fixedLoopDefinition(3));

        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("run-loop-cap", plan, Map.of());

        assertThat(result.steps().stream().filter(step -> "decrement".equals(step.nodeId()))).hasSize(3);
        assertLoopIterations(result, 3);
    }

    @Test
    void runsDynamicToolSequenceWithUniqueSyntheticNodeIds() {
        WorkflowRuntimeTestSupport.RuntimeStack stack = simpleStack();
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("dyn_1", "dynamic", Map.of("itemsFrom", "{{input.tools}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "dyn_1"),
                        new WorkflowEdge("dyn_1", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("run-dynamic", plan, Map.of(
                "tools", List.of("getCurrentTime", "getCurrentTime")));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("dyn_1", "dyn_1:dynamic:0:getCurrentTime", "dyn_1:dynamic:1:getCurrentTime");
    }

    @Test
    void dynamicExecutesMapItemsWithToolArguments() {
        WorkflowRuntimeTestSupport.RuntimeStack stack = simpleStack();
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("dyn_1", "dynamic", Map.of("itemsFrom", "{{input.tools}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "dyn_1"),
                        new WorkflowEdge("dyn_1", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("run-dynamic-map", plan, Map.of(
                "tools", List.of(Map.of("toolName", "calculate", "expression", "1+2"))));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("dyn_1:dynamic:0:calculate");
        WorkflowStepSummary dynamicStep = result.steps().stream()
                .filter(step -> "dyn_1".equals(step.nodeId()))
                .findFirst()
                .orElseThrow();
        assertThat(((Map<?, ?>) dynamicStep.output()).get("itemCount")).isEqualTo(1);
    }

    @Test
    void subgraphThenLoopPreservesActiveExecutionPlan() {
        WorkflowDefinition childDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_1", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_1"),
                        new WorkflowEdge("tool_1", "end")));

        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        when(definitionService.resolveDefinition("child-wf", null))
                .thenReturn(new WorkflowDefinitionResolution("child-wf", 1, childDefinition));

        WorkflowRuntimeTestSupport.RuntimeStack stack = WorkflowRuntimeTestSupport.simpleRuntimeStack(
                definitionService,
                mock(RagService.class),
                mock(AiModelService.class),
                WorkflowRuntimeTestSupport.localToolGateway(),
                com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(),
                WorkflowRuntimeTestSupport.mockPermissiveTraceService(),
                executorService);

        WorkflowExecutionPlan plan = compiler.compile(subgraphThenLoopDefinition());
        WorkflowRuntime.WorkflowExecutionResult result = stack.runtime().run("run-nested", plan,
                Map.of("message", "hello"));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("sub_1", "start", "sub_1::tool_1", "sub_1::start", "sub_1::end",
                        "loop_1", "body_tool", "after_loop", "end");
        WorkflowStepSummary subgraphStep = result.steps().stream()
                .filter(step -> "sub_1".equals(step.nodeId()))
                .findFirst()
                .orElseThrow();
        assertThat(((Map<?, ?>) subgraphStep.output()).get("definitionId")).isEqualTo("child-wf");
        assertThat(((Map<?, ?>) subgraphStep.output()).get("nestedStepCount")).isEqualTo(3);
    }

    @Test
    void simpleAndGraphRuntimeProduceSameLoopStepCount() {
        WorkflowExecutionPlan plan = compiler.compile(fixedLoopDefinition(2));
        Map<String, Object> input = Map.of("message", "parity");

        WorkflowRuntimeTestSupport.RuntimeStack simpleStack = simpleStack();
        WorkflowRuntimeTestSupport.GraphRuntimeStack graphStack = WorkflowRuntimeTestSupport.graphRuntimeStack(
                mock(WorkflowDefinitionService.class),
                mock(RagService.class),
                mock(AiModelService.class),
                WorkflowRuntimeTestSupport.localToolGateway(),
                com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(),
                WorkflowRuntimeTestSupport.mockPermissiveTraceService(),
                executorService);

        long simpleBodySteps = simpleStack.runtime().run("simple-parity", plan, input).steps().stream()
                .filter(step -> "decrement".equals(step.nodeId()))
                .count();
        long graphBodySteps = graphStack.runtime().run("graph-parity", plan, input).steps().stream()
                .filter(step -> "decrement".equals(step.nodeId()))
                .count();

        assertThat(simpleBodySteps).isEqualTo(2);
        assertThat(graphBodySteps).isEqualTo(simpleBodySteps);
    }

    private WorkflowRuntimeTestSupport.RuntimeStack simpleStack() {
        return WorkflowRuntimeTestSupport.simpleRuntimeStack(
                mock(WorkflowDefinitionService.class),
                mock(RagService.class),
                mock(AiModelService.class),
                WorkflowRuntimeTestSupport.localToolGateway(),
                com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(),
                WorkflowRuntimeTestSupport.mockPermissiveTraceService(),
                executorService);
    }

    private void assertLoopIterations(WorkflowRuntime.WorkflowExecutionResult result, int expectedIterations) {
        WorkflowStepSummary loopStep = result.steps().stream()
                .filter(step -> "loop_1".equals(step.nodeId()))
                .findFirst()
                .orElseThrow();
        assertThat(loopStep.output()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) loopStep.output()).get("iterations")).isEqualTo(expectedIterations);
    }

    private WorkflowDefinition parallelWithBranchNode(String nodeId, String type, Map<String, Object> config) {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode(nodeId, type, config),
                        new WorkflowNode("join_1", "join", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "parallel_1"),
                        new WorkflowEdge("parallel_1", nodeId),
                        new WorkflowEdge("parallel_1", "join_1"),
                        new WorkflowEdge(nodeId, "join_1"),
                        new WorkflowEdge("join_1", "end")));
    }

    private WorkflowDefinition loopDefinition() {
        return fixedLoopDefinition(5);
    }

    private WorkflowDefinition fixedLoopDefinition(int maxIterations) {
        return fixedLoopDefinition(maxIterations, Map.of());
    }

    private WorkflowDefinition fixedLoopDefinition(int maxIterations, Map<String, Object> extraLoopConfig) {
        java.util.Map<String, Object> loopConfig = new java.util.LinkedHashMap<>();
        loopConfig.put("maxIterations", maxIterations);
        loopConfig.put("left", "1");
        loopConfig.put("operator", "equals");
        loopConfig.put("right", "1");
        loopConfig.putAll(extraLoopConfig);
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("loop_1", "loop", loopConfig),
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
