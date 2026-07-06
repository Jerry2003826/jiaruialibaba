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
import static org.assertj.core.api.Assertions.catchThrowable;
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
    void llmNodeParsesJsonObjectAnswerIntoParsedOutput() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("""
                        {"intent":"order_query","confidence":0.93,"orderIds":["20260630001"]}
                        """, null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of("prompt", "Classify: {{input.message}}")),
                state);

        assertThat(output).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsKey("parsed");
            Map<?, ?> parsed = (Map<?, ?>) map.get("parsed");
            assertThat(parsed.get("intent")).isEqualTo("order_query");
            assertThat(parsed.get("confidence")).isEqualTo(0.93);
            assertThat(parsed.get("orderIds")).isEqualTo(List.of("20260630001"));
        });
    }

    @Test
    void llmNodeRequiresJsonWhenOutputModeIsJson() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("I think this is an order question.", null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json"
                )), state)
        )
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_LLM_OUTPUT_INVALID"))
                .hasMessageContaining("must be valid JSON");
    }

    @Test
    void llmNodeRejectsJsonOutputMissingRequiredSchemaField() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("{\"confidence\":0.91}", null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", intentSchema()
                )), state))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_LLM_OUTPUT_INVALID"))
                .hasMessageContaining("Missing required workflow node output field: intent");
    }

    @Test
    void llmNodeRejectsJsonOutputEnumOutsideSchemaContract() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("{\"intent\":\"do_anything\",\"confidence\":0.91}", null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", intentSchema()
                )), state))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_LLM_OUTPUT_INVALID"))
                .hasMessageContaining("Workflow node output field intent must be one of");
    }

    @Test
    void llmNodeRejectsAdditionalJsonFieldsAndDoesNotWriteInvalidState() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("""
                        {"intent":"order_query","confidence":0.91,"freeformAction":"call_any_tool"}
                        """, null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Throwable thrown = catchThrowable(() -> executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", intentSchema(),
                        "writeState", Map.of("intent", "{{lastOutput.parsed.intent}}")
                )), state));

        assertThat(thrown)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_LLM_OUTPUT_INVALID"))
                .hasMessageContaining("Unsupported workflow node output field: freeformAction");
        assertThat(state.stateVariables()).doesNotContainKey("intent");
        assertThat(state.answer()).isNull();
    }

    @Test
    void llmNodeAcceptsJsonSchemaAndAllowsWriteState() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("""
                        {"intent":"order_query","confidence":0.91}
                        """, null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", intentSchema(),
                        "writeState", Map.of(
                                "intent", "{{lastOutput.parsed.intent}}",
                                "confidence", "{{lastOutput.parsed.confidence}}"
                        )
                )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsKey("parsed");
            Map<?, ?> parsed = (Map<?, ?>) map.get("parsed");
            assertThat(parsed.get("intent")).isEqualTo("order_query");
            assertThat(parsed.get("confidence")).isEqualTo(0.91);
        });
        assertThat(state.stateVariables())
                .containsEntry("intent", "order_query")
                .containsEntry("confidence", 0.91);
        assertThat(state.answer()).contains("order_query");
    }

    @Test
    void nodeWritesExplicitWorkflowStateAfterSuccessfulExecution() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of(
                "message", "hello",
                "order", Map.of("status", "SHIPPED")));

        executor.execute("run-1", new WorkflowNode("start", "start", Map.of(
                "writeState", Map.of(
                        "intent", "order_query",
                        "message", "{{lastOutput.message}}",
                        "order", "{{input.order}}"
                )
        )), state);

        assertThat(state.stateVariables())
                .containsEntry("intent", "order_query")
                .containsEntry("message", "hello")
                .containsEntry("order", Map.of("status", "SHIPPED"));
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

    @Test
    void conditionNodeSupportsAllCompositeConditions() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));
        state.setStateVariable("intent", "order_query");
        state.setStateVariable("order", Map.of("status", "SHIPPED"));

        Object output = executor.execute("run-1", new WorkflowNode("check", "condition", Map.of(
                "mode", "all",
                "conditions", List.of(
                        Map.of("left", "{{state.intent}}", "operator", "equals", "right", "order_query"),
                        Map.of("left", "{{state.order.status}}", "operator", "equals", "right", "SHIPPED")
                )
        )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map).containsEntry("result", true));
        assertThat(state.lastConditionResult()).isTrue();
    }

    @Test
    void conditionNodeAllCompositeConditionsRequiresEveryCondition() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));
        state.setStateVariable("intent", "order_query");
        state.setStateVariable("order", Map.of("status", "PENDING_RETURN"));

        Object output = executor.execute("run-1", new WorkflowNode("check", "condition", Map.of(
                "mode", "all",
                "conditions", List.of(
                        Map.of("left", "{{state.intent}}", "operator", "equals", "right", "order_query"),
                        Map.of("left", "{{state.order.status}}", "operator", "equals", "right", "SHIPPED")
                )
        )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map).containsEntry("result", false));
        assertThat(state.lastConditionResult()).isFalse();
    }

    @Test
    void conditionNodeSupportsAnyCompositeConditions() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));
        state.setStateVariable("intent", "order_query");
        state.setStateVariable("order", Map.of("status", "SHIPPED"));

        Object output = executor.execute("run-1", new WorkflowNode("check", "condition", Map.of(
                "mode", "any",
                "conditions", List.of(
                        Map.of("left", "{{state.intent}}", "operator", "equals", "right", "product_consult"),
                        Map.of("left", "{{state.order.status}}", "operator", "equals", "right", "SHIPPED")
                )
        )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map).containsEntry("result", true));
        assertThat(state.lastConditionResult()).isTrue();
    }

    @Test
    void conditionNodeIgnoresEmptyCompositeConditionsDefaultAndUsesLegacyConfig() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1", new WorkflowNode("check", "condition", Map.of(
                "left", "{{input.message}}",
                "operator", "equals",
                "right", "hello",
                "conditions", List.of()
        )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map).containsEntry("result", true));
        assertThat(state.lastConditionResult()).isTrue();
    }

    private Map<String, Object> intentSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("intent", "confidence"),
                "properties", Map.of(
                        "intent", Map.of("type", "string",
                                "enum", List.of("order_query", "refund_policy", "unknown")),
                        "confidence", Map.of("type", "number"),
                        "orderId", Map.of("type", "string")),
                "additionalProperties", false);
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
