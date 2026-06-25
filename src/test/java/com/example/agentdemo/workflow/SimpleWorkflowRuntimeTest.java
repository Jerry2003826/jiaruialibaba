package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleWorkflowRuntimeTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final WorkflowVariableResolver variableResolver = new WorkflowVariableResolver();
    private final ExecutorService executorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name("simple-workflow-test-node-", 0)
            .factory());

    @AfterEach
    void shutdownExecutorService() {
        executorService.shutdownNow();
    }

    @Test
    void runsOnlyMatchingConditionBranch() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(new ToolService())));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        SimpleWorkflowRuntime runtime = new SimpleWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class), gateway,
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-1", conditionalPlan(),
                Map.of("message", "what time is it?", "intent", "time"));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .containsExactly("start", "check_intent", "tool_time", "end");
        verify(traceService).completeStep(eq("step-workflow_node_tool_time"), any());
    }

    @Test
    void failedToolNodeWritesToolExecutionLogToTraceOutput() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new FailingRemoteProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_fail"));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        SimpleWorkflowRuntime runtime = new SimpleWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class), gateway,
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
                        && ToolExecutionLog.ERROR_REMOTE_TOOL.equals(log.errorCategory())));
    }

    @Test
    void laterNodeCanReferenceEarlierNodeOutput() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new MapEchoProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("map_echo"));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        SimpleWorkflowRuntime runtime = new SimpleWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class), gateway,
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_first", "tool", Map.of(
                                "toolName", "map_echo",
                                "arguments", Map.of("text", "from first"))),
                        new WorkflowNode("tool_second", "tool", Map.of(
                                "toolName", "map_echo",
                                "arguments", Map.of("text", "{{nodes.tool_first.text}}"))),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_first"),
                        new WorkflowEdge("tool_first", "tool_second"),
                        new WorkflowEdge("tool_second", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-1", plan, Map.of("message", "hello"));

        assertThat(result.output()).isEqualTo(Map.of("text", "from first"));
    }

    @Test
    void runsParallelBranchesAndMergesAtJoin() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new MapEchoProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("map_echo"));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        SimpleWorkflowRuntime runtime = new SimpleWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class), gateway,
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode("tool_a", "tool", Map.of(
                                "toolName", "map_echo",
                                "arguments", Map.of("text", "A"))),
                        new WorkflowNode("tool_b", "tool", Map.of(
                                "toolName", "map_echo",
                                "arguments", Map.of("text", "B"))),
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

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .containsExactly("start", "parallel_1", "tool_a", "tool_b", "join_1", "end");
        assertThat(result.output()).isInstanceOf(Map.class);
        Map<?, ?> output = (Map<?, ?>) result.output();
        assertThat(output.containsKey("branchOutputs")).isTrue();
        Map<?, ?> branchOutputs = (Map<?, ?>) output.get("branchOutputs");
        assertThat(branchOutputs.get("tool_a")).isEqualTo(Map.of("text", "A"));
        assertThat(branchOutputs.get("tool_b")).isEqualTo(Map.of("text", "B"));
        verify(traceService).completeStep(eq("step-workflow_node_join_1"),
                argThat(traceOutput -> traceOutput instanceof Map<?, ?> map && map.containsKey("branchOutputs")));
    }

    @Test
    void retriesConfiguredNodeAndWritesAttemptsToTrace() {
        FlakyProvider provider = new FlakyProvider();
        ToolGatewayService gateway = new ToolGatewayService(List.of(provider),
                ToolExecutionPolicy.allowOnlyRemoteTools("flaky_echo"));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        SimpleWorkflowRuntime runtime = new SimpleWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class), gateway,
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_1", "tool", Map.of(
                                "toolName", "flaky_echo",
                                "retryCount", 1)),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_1"),
                        new WorkflowEdge("tool_1", "end"))));

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-1", plan, Map.of("message", "hello"));

        assertThat(provider.attemptCount()).isEqualTo(2);
        assertThat(result.output()).isEqualTo(Map.of("text", "ok"));
        verify(traceService).completeStep(eq("step-workflow_node_tool_1"), argThat(output ->
                hasAttemptCount(output, 2)));
    }

    @Test
    void timesOutConfiguredNodeAndWritesFailedAttemptToTrace() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new SlowProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("slow_echo"));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        SimpleWorkflowRuntime runtime = new SimpleWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class), gateway,
                        variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed()),
                traceService, executorService);

        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_1", "tool", Map.of(
                                "toolName", "slow_echo",
                                "timeoutMs", 10)),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_1"),
                        new WorkflowEdge("tool_1", "end"))));

        assertThatThrownBy(() -> runtime.run("run-1", plan, Map.of("message", "hello")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_NODE_TIMEOUT"));

        verify(traceService).failStep(eq("step-workflow_node_tool_1"), any(BusinessException.class),
                argThat(SimpleWorkflowRuntimeTest::hasFailedAttempt));
    }

    private WorkflowExecutionPlan conditionalPlan() {
        return compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("check_intent", "condition", Map.of(
                                "left", "{{input.intent}}",
                                "operator", "equals",
                                "right", "time")),
                        new WorkflowNode("tool_time", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("llm_fallback", "llm", Map.of("prompt", "Answer {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "check_intent"),
                        new WorkflowEdge("check_intent", "tool_time", "true"),
                        new WorkflowEdge("check_intent", "llm_fallback", "false"),
                        new WorkflowEdge("tool_time", "end"),
                        new WorkflowEdge("llm_fallback", "end"))));
    }

    private static TraceStep step(String nodeName) {
        return new TraceStep("step-" + nodeName, "run-1", nodeName);
    }

    private static boolean hasAttemptCount(Object output, int expected) {
        return output instanceof Map<?, ?> map
                && map.get("attempts") instanceof List<?> attempts
                && attempts.size() == expected;
    }

    private static boolean hasFailedAttempt(Object output) {
        return output instanceof Map<?, ?> map
                && map.get("attempts") instanceof List<?> attempts
                && !attempts.isEmpty()
                && attempts.getFirst() instanceof Map<?, ?> attempt
                && "FAILED".equals(attempt.get("status"));
    }

    private static final class FailingRemoteProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "remote_fail".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            return ToolExecutionLog.failure(toolName, arguments, "remote failed", now, now,
                    new ToolDescriptor(toolName, "Remote failure", providerName(), true),
                    ToolExecutionLog.ERROR_REMOTE_TOOL);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("remote_fail", "Remote failure", providerName(), true));
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

    private static final class FlakyProvider implements ToolProvider {

        private final AtomicInteger attempts = new AtomicInteger();

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "flaky_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            int attempt = attempts.incrementAndGet();
            Instant now = Instant.now();
            ToolDescriptor descriptor = new ToolDescriptor(toolName, "Flaky echo", providerName(), true);
            if (attempt == 1) {
                return ToolExecutionLog.failure(toolName, arguments, "temporary failure", now, now,
                        descriptor, ToolExecutionLog.ERROR_REMOTE_TOOL);
            }
            return ToolExecutionLog.success(toolName, arguments, Map.of("text", "ok"), now, now, descriptor);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("flaky_echo", "Flaky echo", providerName(), true));
        }

        int attemptCount() {
            return attempts.get();
        }

    }

    private static final class SlowProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "slow_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant startedAt = Instant.now();
            try {
                Thread.sleep(500);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            Instant endedAt = Instant.now();
            ToolDescriptor descriptor = new ToolDescriptor(toolName, "Slow echo", providerName(), true);
            return ToolExecutionLog.success(toolName, arguments, Map.of("text", "late"), startedAt, endedAt,
                    descriptor);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("slow_echo", "Slow echo", providerName(), true));
        }

    }

}
