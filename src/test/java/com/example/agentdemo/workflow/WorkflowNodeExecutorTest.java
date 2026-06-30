package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.TokenUsage;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.rag.dto.RetrievedContext;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowNodeExecutorTest {

    private final WorkflowVariableResolver variableResolver = new WorkflowVariableResolver();

    @Test
    void retrieverNodeRendersQueryTemplateBeforeSearching() {
        RagService ragService = mock(RagService.class);
        RetrievedContext context = new RetrievedContext(1L, "Doc", "content", 0.9);
        when(ragService.retrieve("hello", 3, "run-1")).thenReturn(List.of(context));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(ragService, mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("retriever_1", "retriever", Map.of("query", "{{input.message}}", "topK", 3)),
                state);

        verify(ragService).retrieve("hello", 3, "run-1");
        assertThat(output).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsEntry("query", "hello");
            assertThat(map).containsEntry("retrievedContext", List.of(context));
        });
    }

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
    void throwsWhenLlmReturnsFallback() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(AiModelResult.fallback("mock fallback payload", "model unavailable"));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(com.example.agentdemo.workflow.WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1", new WorkflowNode("llm_1", "llm", Map.of()), state))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_UNAVAILABLE");
    }

    @Test
    void llmNodeUsesConfiguredModelAndReturnsTokenUsageDetails() {
        AiModelService aiModelService = mock(AiModelService.class);
        TokenUsage usage = new TokenUsage("dashscope", "qwen-max", 14, 9, 23,
                Map.of("input_tokens", 14, "output_tokens", 9));
        when(aiModelService.generateWithModel(anyString(), eq("Summarize: hello"), eq("qwen-max")))
                .thenReturn(AiModelResult.ok("summary", usage));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("llm_1", "llm", Map.of(
                        "prompt", "Summarize: {{input.message}}",
                        "model", "qwen-max"
                )),
                state);

        assertThat(output).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsEntry("answer", "summary");
            assertThat(map).containsEntry("model", "qwen-max");
            assertThat(map).containsEntry("tokenUsage", usage);
        });
        verify(aiModelService).generateWithModel(anyString(), eq("Summarize: hello"), eq("qwen-max"));
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

    @Test
    void evaluateConditionSupportsNumericComparisons() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));

        assertThat(executor.evaluateCondition("5", "greaterthan", "3", false)).isTrue();
        assertThat(executor.evaluateCondition("2", "lessthan", "4", false)).isTrue();
        assertThat(executor.evaluateCondition("5", "lessthan", "3", false)).isFalse();
    }

    @Test
    void evaluateConditionRejectsNonNumericValuesForNumericOperators() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));

        assertThatThrownBy(() -> executor.evaluateCondition("abc", "greaterthan", "3", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Numeric comparison requires numeric left/right values");
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
