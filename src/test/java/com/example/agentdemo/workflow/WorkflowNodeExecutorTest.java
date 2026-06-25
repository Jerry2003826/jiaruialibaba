package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.support.TestAlibabaPolicies;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolExecutionPolicy;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowNodeExecutorTest {

    private final WorkflowVariableResolver variableResolver = new WorkflowVariableResolver();

    @Test
    void toolNodeUsesToolGatewaySoRemoteToolsCanBeCalled() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new RemoteEchoProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_echo"));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                gateway, variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(), mock(com.example.agentdemo.workflow.WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("mcp_1", "tool", Map.of(
                        "toolName", "remote_echo",
                        "arguments", Map.of("text", "{{input}}")
                )),
                state);

        assertThat(output).isInstanceOf(ToolExecutionLog.class);
        assertThat(state.lastOutput()).isEqualTo("remote:hello");
        assertThat(state.toolCalls()).hasSize(1);
    }

    @Test
    void throwsWhenStrictModeEnabledAndLlmReturnsFallback() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(AiModelResult.fallback("Workflow fallback answer. Prompt: test", "model unavailable"));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(), mock(com.example.agentdemo.workflow.WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1", new WorkflowNode("llm_1", "llm", Map.of()), state))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_UNAVAILABLE");
    }

    @Test
    void failedToolNodeThrowsBusinessExceptionWithToolErrorCategory() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new FailingRemoteProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_fail"));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                gateway, variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(), mock(com.example.agentdemo.workflow.WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1",
                new WorkflowNode("mcp_1", "tool", Map.of("toolName", "remote_fail")),
                state))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ToolExecutionLog.ERROR_REMOTE_TOOL));
    }

    private static final class RemoteEchoProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "remote_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            return new ToolExecutionLog(toolName, arguments, "remote:" + arguments.get("text"), true, null, now, now);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("remote_echo", "Remote echo", providerName(), true));
        }

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
