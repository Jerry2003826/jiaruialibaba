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
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider),
                "github")),
                ToolExecutionPolicy.allowOnlyRemoteTools("github:remote_echo"));

        ToolExecutionLog log = gateway.execute("remote_echo", Map.of("text", "hello mcp"));

        assertThat(log.succeeded()).isTrue();
        assertThat(log.toolName()).isEqualTo("remote_echo");
        assertThat(log.provider()).isEqualTo("mcp");
        assertThat(log.remote()).isTrue();
        assertThat(log.serverName()).isEqualTo("github");
        assertThat(log.durationMs()).isGreaterThanOrEqualTo(0);
        assertThat(log.errorCategory()).isNull();
        assertThat(log.errorType()).isNull();
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
    void allowsLegacyToolNameAllowlistEntriesForBackwardsCompatibility() {
        ToolDescriptor descriptor = new ToolDescriptor("remote_echo", "Echo text", "mcp", true, "github", "{}");
        ToolExecutionPolicy policy = ToolExecutionPolicy.allowOnlyRemoteTools("remote_echo");

        assertThat(policy.canExecute(descriptor)).isTrue();
    }

    @Test
    void supportsServerScopedRemoteToolAllowlistEntries() {
        ToolDescriptor githubDescriptor = new ToolDescriptor("remote_echo", "Echo text", "mcp", true, "github", "{}");
        ToolDescriptor otherDescriptor = new ToolDescriptor("remote_echo", "Echo text", "mcp", true, "other", "{}");
        ToolExecutionPolicy policy = ToolExecutionPolicy.allowOnlyRemoteTools("github:remote_echo");

        assertThat(policy.canExecute(githubDescriptor)).isTrue();
        assertThat(policy.canExecute(otherDescriptor)).isFalse();
    }

    @Test
    void preservesNullArgumentsForNullableMcpToolSchema() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new NullableEchoToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_nullable_echo"));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", null);

        ToolExecutionLog log = gateway.execute("remote_nullable_echo", arguments);

        assertThat(log.succeeded()).isTrue();
        assertThat(log.output()).asString().contains("\"text\":null");
    }

    @Test
    void rejectsMcpToolWhenRequiredArgumentIsMissing() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new EchoToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider),
                "github")),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_echo"));

        ToolExecutionLog log = gateway.execute("remote_echo", Map.of());

        assertThat(log.succeeded()).isFalse();
        assertThat(log.provider()).isEqualTo("mcp");
        assertThat(log.remote()).isTrue();
        assertThat(log.errorCategory()).isEqualTo("VALIDATION_ERROR");
        assertThat(log.errorType()).isEqualTo(ToolExecutionLog.ERROR_TYPE_NORMAL);
        assertThat(log.errorMessage()).contains("Missing required MCP tool argument: text");
    }

    @Test
    void rejectsMcpToolWhenArgumentTypeDoesNotMatchSchema() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new EchoToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_echo"));

        ToolExecutionLog log = gateway.execute("remote_echo", Map.of("text", 123));

        assertThat(log.succeeded()).isFalse();
        assertThat(log.errorCategory()).isEqualTo("VALIDATION_ERROR");
        assertThat(log.errorMessage()).contains("MCP tool argument text must be string");
    }

    @Test
    void rejectsMcpToolWhenNullDoesNotMatchSchemaType() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new EchoToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_echo"));
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("text", null);

        ToolExecutionLog log = gateway.execute("remote_echo", arguments);

        assertThat(log.succeeded()).isFalse();
        assertThat(log.errorCategory()).isEqualTo("VALIDATION_ERROR");
        assertThat(log.errorMessage()).contains("MCP tool argument text must be string");
    }

    @Test
    void acceptsMcpToolArgumentMatchingAnyNonNullUnionType() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new UnionTypeToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_union"));

        ToolExecutionLog log = gateway.execute("remote_union", Map.of("value", 42));

        assertThat(log.succeeded()).isTrue();
        assertThat(log.output()).asString().contains("\"value\":42");
    }

    @Test
    void exposesMcpInputSchemaInToolDescriptor() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new EchoToolCallback());
        McpToolProvider provider = new McpToolProvider(List.of(callbackProvider), "github");

        ToolDescriptor descriptor = provider.tools().getFirst();

        assertThat(descriptor.serverName()).isEqualTo("github");
        assertThat(descriptor.inputSchema()).contains("\"required\": [\"text\"]");
    }

    @Test
    void classifiesRemoteRuntimeExceptionAsRawRemoteError() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new FailingToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider),
                "github")),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_fail"));

        ToolExecutionLog log = gateway.execute("remote_fail", Map.of());

        assertThat(log.succeeded()).isFalse();
        assertThat(log.serverName()).isEqualTo("github");
        assertThat(log.errorCategory()).isEqualTo(ToolExecutionLog.ERROR_REMOTE_TOOL);
        assertThat(log.errorType()).isEqualTo(ToolExecutionLog.ERROR_TYPE_RAW_REMOTE);
    }

    @Test
    void usesFallbackMessageWhenRemoteExceptionMessageIsNull() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new NullMessageFailingToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider),
                "github")),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_null_message_fail"));

        ToolExecutionLog log = gateway.execute("remote_null_message_fail", Map.of());

        assertThat(log.succeeded()).isFalse();
        assertThat(log.errorMessage()).isEqualTo("Remote MCP tool failed: remote_null_message_fail");
        assertThat(log.errorType()).isEqualTo(ToolExecutionLog.ERROR_TYPE_RAW_REMOTE);
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

    private static final class NullableEchoToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_nullable_echo")
                    .description("Echo nullable text from a remote MCP server")
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "text": {"type": ["string", "null"]}
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

    private static final class UnionTypeToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_union")
                    .description("Echo string or number from a remote MCP server")
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "value": {"type": ["string", "number"]}
                              },
                              "required": ["value"]
                            }
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "mcp:" + toolInput;
        }

    }

    private static final class FailingToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_fail")
                    .description("Fail from a remote MCP server")
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {}
                            }
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException("raw remote failure");
        }

    }

    private static final class NullMessageFailingToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_null_message_fail")
                    .description("Fail from a remote MCP server without a message")
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {}
                            }
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            throw new IllegalStateException();
        }

    }

}
