package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.tool.LocalToolProvider;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolExecutionPolicy;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolProvider;
import com.example.agentdemo.tool.ToolService;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphWorkflowRuntimeTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final WorkflowVariableResolver variableResolver = new WorkflowVariableResolver();
    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name("graph-workflow-test-node-", 0)
            .factory());

    @AfterEach
    void shutdownExecutorService() {
        executorService.shutdownNow();
    }

    @Test
    void runsLinearWorkflowThroughSpringAiAlibabaGraph() {
        RagService ragService = mock(RagService.class);
        AiModelService aiModelService = mock(AiModelService.class);
        ToolGatewayService toolGatewayService = new ToolGatewayService(
                List.of(new LocalToolProvider(new ToolService())));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        when(aiModelService.generate(any(), any()))
                .thenReturn(AiModelResult.ok("graph answer"));

        GraphWorkflowRuntime runtime = new GraphWorkflowRuntime(
                new WorkflowNodeExecutor(ragService, aiModelService, toolGatewayService, variableResolver,
                        com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-1", compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm_1", "llm", Map.of("prompt", "Question: {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "llm_1"),
                        new WorkflowEdge("llm_1", "end")))),
                Map.of("message", "hello graph"));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .containsExactly("start", "llm_1", "end");
        assertThat(result.output()).asString().contains("graph answer");
    }

    @Test
    void runsConditionBranchWorkflowThroughSpringAiAlibabaGraph() {
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-branch"), any(), any()))
                .thenAnswer(invocation -> stepForRun("run-branch", invocation.getArgument(1)));
        GraphWorkflowRuntime runtime = new GraphWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                        new ToolGatewayService(List.of(new LocalToolProvider(new ToolService()))),
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("check", "condition", Map.of("left", "{{input.intent}}")),
                        new WorkflowNode("time", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("fallback", "llm", Map.of("prompt", "Answer {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "check"),
                        new WorkflowEdge("check", "time", "true"),
                        new WorkflowEdge("check", "fallback", "false"),
                        new WorkflowEdge("time", "end"),
                        new WorkflowEdge("fallback", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-branch", plan,
                Map.of("message", "what time is it?", "intent", "time"));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .containsExactly("start", "check", "time", "end");
        verify(traceService).completeStep(eq("step-workflow_node_time"), any());
    }

    @Test
    void failedToolNodeWritesToolExecutionLogToTraceOutput() {
        ToolGatewayService toolGatewayService = new ToolGatewayService(List.of(new FailingRemoteProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("github:remote_fail"));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        GraphWorkflowRuntime runtime = new GraphWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class), toolGatewayService,
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_1", "tool", Map.of("toolName", "remote_fail")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_1"),
                        new WorkflowEdge("tool_1", "end"))));

        assertThatThrownBy(() -> runtime.run("run-1", plan, Map.of("message", "hello")))
                .isInstanceOf(RuntimeException.class);

        verify(traceService).failStep(eq("step-workflow_node_tool_1"), any(RuntimeException.class),
                argThat(output -> output instanceof ToolExecutionLog log
                        && "remote_fail".equals(log.toolName())
                        && "github".equals(log.serverName())
                        && ToolExecutionLog.ERROR_REMOTE_TOOL.equals(log.errorCategory())));
    }

    @Test
    void runsParallelJoinWorkflowThroughSpringAiAlibabaGraph() {
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        GraphWorkflowRuntime runtime = new GraphWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                        new ToolGatewayService(List.of(new LocalToolProvider(new ToolService()))),
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode("tool_a", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("tool_b", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("join_1", "join", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "parallel_1"),
                        new WorkflowEdge("parallel_1", "tool_a"),
                        new WorkflowEdge("parallel_1", "tool_b"),
                        new WorkflowEdge("tool_a", "join_1"),
                        new WorkflowEdge("tool_b", "join_1"),
                        new WorkflowEdge("join_1", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-1", plan, Map.of("message", "hello"));

        List<String> nodeIds = result.steps().stream().map(WorkflowStepSummary::nodeId).toList();
        assertThat(nodeIds).containsExactlyInAnyOrder("start", "parallel_1", "tool_a", "tool_b", "join_1", "end");
        assertThat(nodeIds.indexOf("start")).isLessThan(nodeIds.indexOf("parallel_1"));
        assertThat(nodeIds.indexOf("tool_a")).isLessThan(nodeIds.indexOf("join_1"));
        assertThat(nodeIds.indexOf("tool_b")).isLessThan(nodeIds.indexOf("join_1"));
        assertThat(nodeIds.indexOf("join_1")).isLessThan(nodeIds.indexOf("end"));
        assertThat(result.output()).isInstanceOf(Map.class);
        Map<?, ?> output = (Map<?, ?>) result.output();
        assertThat(output.containsKey("branchOutputs")).isTrue();
        Map<?, ?> branchOutputs = (Map<?, ?>) output.get("branchOutputs");
        assertThat(branchOutputs.containsKey("tool_a")).isTrue();
        assertThat(branchOutputs.containsKey("tool_b")).isTrue();
        verify(traceService).completeStep(eq("step-workflow_node_join_1"),
                argThat(traceOutput -> traceOutput instanceof Map<?, ?> map && map.containsKey("branchOutputs")));
    }

    @Test
    void parallelBranchesKeepLastOutputIsolatedUntilJoin() {
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        GraphWorkflowRuntime runtime = new GraphWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                        new ToolGatewayService(List.of(new MapEchoProvider()),
                                ToolExecutionPolicy.allowOnlyRemoteTools("map_echo")),
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode("a_first", "tool", Map.of(
                                "toolName", "map_echo",
                                "arguments", Map.of("text", "A"))),
                        new WorkflowNode("a_second", "tool", Map.of(
                                "toolName", "map_echo",
                                "arguments", Map.of("text", "{{lastOutput.text}}-2"))),
                        new WorkflowNode("b_first", "tool", Map.of(
                                "toolName", "map_echo",
                                "arguments", Map.of("text", "B"))),
                        new WorkflowNode("b_second", "tool", Map.of(
                                "toolName", "map_echo",
                                "arguments", Map.of("text", "{{lastOutput.text}}-2"))),
                        new WorkflowNode("join_1", "join", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "parallel_1"),
                        new WorkflowEdge("parallel_1", "a_first"),
                        new WorkflowEdge("parallel_1", "b_first"),
                        new WorkflowEdge("a_first", "a_second"),
                        new WorkflowEdge("a_second", "join_1"),
                        new WorkflowEdge("b_first", "b_second"),
                        new WorkflowEdge("b_second", "join_1"),
                        new WorkflowEdge("join_1", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-1", plan, Map.of("message", "hello"));

        assertThat(result.output()).isInstanceOf(Map.class);
        Map<?, ?> output = (Map<?, ?>) result.output();
        assertThat(output.get("branchOutputs")).isInstanceOf(Map.class);
        Map<?, ?> branchOutputs = (Map<?, ?>) output.get("branchOutputs");
        assertThat(branchOutputs.get("a_first")).isEqualTo(Map.of("text", "A-2"));
        assertThat(branchOutputs.get("b_first")).isEqualTo(Map.of("text", "B-2"));
        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("a_first", "a_second", "b_first", "b_second");
        verify(traceService).completeStep(eq("step-workflow_node_a_second"), any());
        verify(traceService).completeStep(eq("step-workflow_node_b_second"), any());
    }

    @Test
    void supportsDirectEmptyBranchFromParallelToJoin() {
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        GraphWorkflowRuntime runtime = new GraphWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                        new ToolGatewayService(List.of(new MapEchoProvider()),
                                ToolExecutionPolicy.allowOnlyRemoteTools("map_echo")),
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode("tool_a", "tool", Map.of(
                                "toolName", "map_echo",
                                "arguments", Map.of("text", "A"))),
                        new WorkflowNode("join_1", "join", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "parallel_1"),
                        new WorkflowEdge("parallel_1", "tool_a"),
                        new WorkflowEdge("parallel_1", "join_1"),
                        new WorkflowEdge("tool_a", "join_1"),
                        new WorkflowEdge("join_1", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-1", plan, Map.of("message", "hello"));

        assertThat(result.output()).isInstanceOf(Map.class);
        Map<?, ?> output = (Map<?, ?>) result.output();
        assertThat(output.get("branchOutputs")).isInstanceOf(Map.class);
        Map<?, ?> branchOutputs = (Map<?, ?>) output.get("branchOutputs");
        assertThat(branchOutputs.get("tool_a")).isEqualTo(Map.of("text", "A"));
        assertThat(branchOutputs.get("join_1")).isNotNull();
        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .containsExactlyInAnyOrder("start", "parallel_1", "tool_a", "join_1", "end");
    }

    @Test
    void syntheticBranchNodeIdDoesNotCollideWithUserNodeIds() {
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        GraphWorkflowRuntime runtime = new GraphWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                        new ToolGatewayService(List.of(new LocalToolProvider(new ToolService()))),
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode("tool_a", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("tool_b", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("join_1", "join", Map.of()),
                        new WorkflowNode("workflow_branch_parallel_1_tool_a", "tool",
                                Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "parallel_1"),
                        new WorkflowEdge("parallel_1", "tool_a"),
                        new WorkflowEdge("parallel_1", "tool_b"),
                        new WorkflowEdge("tool_a", "join_1"),
                        new WorkflowEdge("tool_b", "join_1"),
                        new WorkflowEdge("join_1", "workflow_branch_parallel_1_tool_a"),
                        new WorkflowEdge("workflow_branch_parallel_1_tool_a", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-1", plan, Map.of("message", "hello"));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .contains("workflow_branch_parallel_1_tool_a", "tool_a", "tool_b", "join_1");
    }

    private static TraceStep step(String nodeName) {
        return new TraceStep("step-" + nodeName, "run-1", nodeName);
    }

    private static TraceStep stepForRun(String runId, String nodeName) {
        return new TraceStep("step-" + nodeName, runId, nodeName);
    }

    private static final class FailingRemoteProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "remote_fail".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            return ToolExecutionLog.failure(toolName, arguments, "remote failed", now, now,
                    new ToolDescriptor(toolName, "Remote failure", providerName(), true, "github", "{}"),
                    ToolExecutionLog.ERROR_REMOTE_TOOL, ToolExecutionLog.ERROR_TYPE_RAW_REMOTE);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("remote_fail", "Remote failure", providerName(), true, "github", "{}"));
        }

    }

    private static final class MapEchoProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "map_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            return ToolExecutionLog.success(toolName, arguments, Map.of("text", arguments.get("text")), now, now,
                    new ToolDescriptor(toolName, "Map echo", providerName(), true));
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("map_echo", "Map echo", providerName(), true));
        }

    }

}
