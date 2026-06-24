package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.tool.LocalToolProvider;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolExecutionPolicy;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolProvider;
import com.example.agentdemo.tool.ToolService;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.TraceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    @Test
    void runsOnlyMatchingConditionBranch() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(new ToolService())));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        SimpleWorkflowRuntime runtime = new SimpleWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class), gateway),
                traceService);

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
        when(traceService.startStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        SimpleWorkflowRuntime runtime = new SimpleWorkflowRuntime(
                new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class), gateway),
                traceService);

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

    private static RunStepEntity step(String nodeName) {
        return new RunStepEntity("step-" + nodeName, "run-1", nodeName, "{}", StepStatus.RUNNING, Instant.now());
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

}
