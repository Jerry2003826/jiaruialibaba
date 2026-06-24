package com.example.agentdemo.tool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolGatewayServiceTest {

    @Test
    void executesLocalToolByName() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(new ToolService())));

        ToolExecutionLog log = gateway.execute("calculate", Map.of("expression", "(12 + 8) / 5"));

        assertThat(log.succeeded()).isTrue();
        assertThat(log.toolName()).isEqualTo("calculate");
        assertThat(log.output()).isEqualTo("4");
        assertThat(log.input()).isInstanceOf(ToolExecutionLog.CalculateInput.class);
    }

    @Test
    void returnsFailedLogForUnknownTool() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(new ToolService())));

        ToolExecutionLog log = gateway.execute("missingTool", Map.of());

        assertThat(log.succeeded()).isFalse();
        assertThat(log.toolName()).isEqualTo("missingTool");
        assertThat(log.errorMessage()).contains("Tool not found");
    }

    @Test
    void routesSpringAiToolCallbacksAsMcpTools() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new EchoToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_echo"));

        ToolExecutionLog log = gateway.execute("remote_echo", Map.of("text", "hello mcp"));

        assertThat(log.succeeded()).isTrue();
        assertThat(log.toolName()).isEqualTo("remote_echo");
        assertThat(log.output()).asString()
                .startsWith("mcp:")
                .contains("hello mcp");
    }

    @Test
    void blocksRemoteToolsThatAreNotAllowedByPolicy() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new EchoToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))));

        ToolExecutionLog log = gateway.execute("remote_echo", Map.of("text", "hello mcp"));

        assertThat(log.succeeded()).isFalse();
        assertThat(log.errorMessage()).contains("not allowed");
    }

    @Test
    void preservesNullArgumentsForMcpTools() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new EchoToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_echo"));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", null);

        ToolExecutionLog log = gateway.execute("remote_echo", arguments);

        assertThat(log.succeeded()).isTrue();
        assertThat(log.output()).asString().contains("\"text\":null");
    }

    private static final class EchoToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_echo")
                    .description("Echo text from a remote MCP server")
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
