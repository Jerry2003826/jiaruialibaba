package com.example.agentdemo.agent;

import com.example.agentdemo.support.TestToolServices;
import com.example.agentdemo.tool.LocalToolProvider;
import com.example.agentdemo.tool.McpToolProvider;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionPolicy;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolProvider;
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
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(TestToolServices.toolService())));
        DemoToolCallbackFactory factory = new DemoToolCallbackFactory(gateway, new ObjectMapper(), emptyMcpProvider());
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), eq("tool_calculate"), any()))
                .thenReturn(new TraceStep("step-1", "run-1", "tool_calculate"));
        List<ToolExecutionLog> toolCalls = new ArrayList<>();

        List<ToolCallback> callbacks = factory.tracedToolCallbacks("run-1", traceService, toolCalls);

        assertThat(callbacks).hasSize(3);
        assertThat(callbacks).allMatch(TracingToolCallback.class::isInstance);
        assertThat(callbackNames(callbacks)).containsExactlyInAnyOrder("getCurrentTime", "calculate",
                "queryOrderAPI");

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
    void exposesOnlyMcpToolCallbacksAllowedByGatewayPolicy() {
        McpToolProvider mcpProvider = rawMcpProvider();
        ToolGatewayService gateway = new ToolGatewayService(
                List.of(new LocalToolProvider(TestToolServices.toolService()), new RemoteEchoProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("github:remote_echo"));
        DemoToolCallbackFactory factory = new DemoToolCallbackFactory(gateway, new ObjectMapper(), mcpProvider(mcpProvider));
        TraceService traceService = mock(TraceService.class);
        List<ToolExecutionLog> toolCalls = new ArrayList<>();

        List<ToolCallback> callbacks = factory.tracedToolCallbacks("run-1", traceService, toolCalls);

        assertThat(callbackNames(callbacks)).contains("remote_echo");
    }

    @Test
    void hidesMcpToolCallbacksDeniedByGatewayPolicy() {
        McpToolProvider mcpProvider = rawMcpProvider();
        ToolGatewayService gateway = new ToolGatewayService(
                List.of(new LocalToolProvider(TestToolServices.toolService()), new RemoteEchoProvider()),
                new ToolExecutionPolicy());
        DemoToolCallbackFactory factory = new DemoToolCallbackFactory(gateway, new ObjectMapper(), mcpProvider(mcpProvider));
        TraceService traceService = mock(TraceService.class);
        List<ToolExecutionLog> toolCalls = new ArrayList<>();

        List<ToolCallback> callbacks = factory.tracedToolCallbacks("run-1", traceService, toolCalls);

        assertThat(callbackNames(callbacks)).doesNotContain("remote_echo");
        assertThat(gateway.execute("remote_echo", Map.of("text", "blocked")).succeeded()).isFalse();
    }

    @Test
    void mcpToolCallbackExecutionReentersGatewayValidation() {
        McpToolProvider mcpProvider = rawMcpProvider();
        ToolGatewayService gateway = new ToolGatewayService(
                List.of(new LocalToolProvider(TestToolServices.toolService()), new RemoteEchoProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("github:remote_echo"));
        DemoToolCallbackFactory factory = new DemoToolCallbackFactory(gateway, new ObjectMapper(), mcpProvider(mcpProvider));
        TraceService traceService = mock(TraceService.class);
        when(traceService.startTraceStep(eq("run-1"), eq("tool_remote_echo"), any()))
                .thenReturn(new TraceStep("step-remote", "run-1", "tool_remote_echo"));
        List<ToolExecutionLog> toolCalls = new ArrayList<>();

        ToolCallback callback = factory.tracedToolCallbacks("run-1", traceService, toolCalls).stream()
                .filter(candidate -> "remote_echo".equals(candidate.getToolDefinition().name()))
                .findFirst()
                .orElseThrow();

        assertThatThrownBy(() -> callback.call("{}"))
                .hasMessageContaining("Missing required MCP tool argument: text");
        assertThat(toolCalls).hasSize(1);
        assertThat(toolCalls.getFirst().succeeded()).isFalse();
        assertThat(toolCalls.getFirst().errorCategory()).isEqualTo(ToolExecutionLog.ERROR_VALIDATION);
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

    private static McpToolProvider rawMcpProvider() {
        McpToolProvider mcpProvider = mock(McpToolProvider.class);
        when(mcpProvider.exposedToolCallbacks()).thenReturn(List.of(new EchoToolCallback()));
        return mcpProvider;
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

    private static final class RemoteEchoProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "remote_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            java.time.Instant now = java.time.Instant.now();
            ToolDescriptor descriptor = tools().getFirst();
            if (!arguments.containsKey("text")) {
                return ToolExecutionLog.failure(toolName, arguments,
                        "Missing required MCP tool argument: text", now, now, descriptor,
                        ToolExecutionLog.ERROR_VALIDATION);
            }
            return ToolExecutionLog.success(toolName, arguments,
                    "mcp:" + arguments.get("text"), now, now, descriptor);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("remote_echo", "Echo text from MCP",
                    "mcp", true, "github", """
                            {
                              "type": "object",
                              "properties": {
                                "text": {"type": "string"}
                              },
                              "required": ["text"]
                            }
                            """));
        }

    }

}
