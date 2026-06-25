package com.example.agentdemo.agent;

import com.example.agentdemo.tool.LocalToolProvider;
import com.example.agentdemo.tool.McpToolProvider;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolService;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoToolCallbackFactoryTest {

    @Test
    void buildsLocalTracedToolCallbacks() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(new ToolService())));
        DemoToolCallbackFactory factory = new DemoToolCallbackFactory(gateway, new ObjectMapper(), emptyMcpProvider());
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), eq("tool_calculate"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "tool_calculate"));
        List<ToolExecutionLog> toolCalls = new ArrayList<>();

        List<ToolCallback> callbacks = factory.tracedToolCallbacks("run-1", traceService, toolCalls);

        assertThat(callbacks).hasSize(2);
        assertThat(callbacks).allMatch(TracingToolCallback.class::isInstance);
        assertThat(callbackNames(callbacks)).containsExactlyInAnyOrder("getCurrentTime", "calculate");

        ToolCallback calculate = callbacks.stream()
                .filter(callback -> "calculate".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();
        String output = calculate.call("{\"expression\":\"(12 + 8) / 5\"}");

        assertThat(output).isEqualTo("4");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().toolName()).isEqualTo("calculate");
        verify(traceService).completeStep(eq("step-1"), any(ToolExecutionLog.class));
    }

    @Test
    void mergesMcpToolCallbacksWhenProviderAvailable() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(new ToolService())));
        McpToolProvider mcpProvider = mock(McpToolProvider.class);
        when(mcpProvider.exposedToolCallbacks()).thenReturn(List.of(new EchoToolCallback()));
        DemoToolCallbackFactory factory = new DemoToolCallbackFactory(gateway, new ObjectMapper(), mcpProvider(mcpProvider));
        TraceService traceService = mock(TraceService.class);
        List<ToolExecutionLog> toolCalls = new ArrayList<>();

        List<ToolCallback> callbacks = factory.tracedToolCallbacks("run-1", traceService, toolCalls);

        assertThat(callbackNames(callbacks)).contains("remote_echo");
    }

    @Test
    void calculateCallbackThrowsWhenGatewayFails() {
        ToolGatewayService gateway = mock(ToolGatewayService.class);
        when(gateway.execute(eq("calculate"), any())).thenReturn(
                ToolExecutionLog.failure("calculate", Map.of("expression", "1/0"), "Division by zero is not allowed",
                        java.time.Instant.now(), java.time.Instant.now(), null, ToolExecutionLog.ERROR_EXECUTION));
        DemoToolCallbackFactory factory = new DemoToolCallbackFactory(gateway, new ObjectMapper(), emptyMcpProvider());
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), eq("tool_calculate"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "tool_calculate"));
        List<ToolExecutionLog> toolCalls = new ArrayList<>();

        ToolCallback calculate = factory.tracedToolCallbacks("run-1", traceService, toolCalls).stream()
                .filter(callback -> "calculate".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> calculate.call("{\"expression\":\"1/0\"}"))
                .hasMessageContaining("Division by zero");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().succeeded()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<McpToolProvider> emptyMcpProvider() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<McpToolProvider> mcpProvider(McpToolProvider provider) {
        ObjectProvider<McpToolProvider> objectProvider = mock(ObjectProvider.class);
        doAnswer(invocation -> {
            Consumer<McpToolProvider> consumer = invocation.getArgument(0);
            consumer.accept(provider);
            return null;
        }).when(objectProvider).ifAvailable(any());
        return objectProvider;
    }

    private static List<String> callbackNames(List<ToolCallback> callbacks) {
        return callbacks.stream().map(callback -> callback.getToolDefinition().name()).toList();
    }

    private static final class EchoToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_echo")
                    .description("Echo text from MCP")
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "text": {"type": "string"}
                              },
                              "required": ["text"]
                            }
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "mcp:" + toolInput;
        }

    }

}
