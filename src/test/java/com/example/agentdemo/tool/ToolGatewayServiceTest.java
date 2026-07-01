package com.example.agentdemo.tool;

import com.example.agentdemo.support.TestToolServices;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolGatewayServiceTest {

    @Test
    void executesLocalToolByName() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(TestToolServices.toolService())));

        ToolExecutionLog log = gateway.execute("calculate", Map.of("expression", "(12 + 8) / 5"));

        assertThat(log.succeeded()).isTrue();
        assertThat(log.toolName()).isEqualTo("calculate");
        assertThat(log.output()).isEqualTo("4");
        assertThat(log.input()).isInstanceOf(ToolExecutionLog.CalculateInput.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executesDemoOrderLookupTool() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(TestToolServices.toolService())));

        ToolExecutionLog log = gateway.execute("queryOrderAPI", Map.of("user_query", "order 20260630001"));

        assertThat(log.succeeded()).isTrue();
        assertThat(log.toolName()).isEqualTo("queryOrderAPI");
        assertThat(log.input()).isInstanceOf(ToolExecutionLog.OrderQueryInput.class);
        assertThat((Map<String, Object>) log.output())
                .containsEntry("orderId", "20260630001")
                .containsEntry("status", "SHIPPED")
                .containsEntry("source", "database:demo_orders");
    }

    @Test
    void returnsFailedLogForUnknownTool() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(TestToolServices.toolService())));

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
    void acceptsMcpToolArgumentMatchingEnumValue() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new EnumToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_enum"));

        ToolExecutionLog log = gateway.execute("remote_enum", Map.of("mode", "read"));

        assertThat(log.succeeded()).isTrue();
        assertThat(log.output()).asString().contains("\"mode\":\"read\"");
    }

    @Test
    void rejectsMcpToolArgumentOutsideEnumValues() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new EnumToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_enum"));

        ToolExecutionLog log = gateway.execute("remote_enum", Map.of("mode", "delete"));

        assertThat(log.succeeded()).isFalse();
        assertThat(log.errorCategory()).isEqualTo(ToolExecutionLog.ERROR_VALIDATION);
        assertThat(log.errorMessage()).contains("MCP tool argument mode must be one of");
    }

    @Test
    void acceptsMcpToolArgumentMatchingOneOfSchema() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new OneOfToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_one_of"));

        ToolExecutionLog log = gateway.execute("remote_one_of", Map.of("target", 42));

        assertThat(log.succeeded()).isTrue();
        assertThat(log.output()).asString().contains("\"target\":42");
    }

    @Test
    void rejectsMcpToolArgumentThatMatchesNoAnyOfSchema() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new AnyOfToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_any_of"));

        ToolExecutionLog log = gateway.execute("remote_any_of", Map.of("target", true));

        assertThat(log.succeeded()).isFalse();
        assertThat(log.errorCategory()).isEqualTo(ToolExecutionLog.ERROR_VALIDATION);
        assertThat(log.errorMessage()).contains("MCP tool argument target must match anyOf schema");
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
        assertThat(log.errorMessage()).isEqualTo("Remote MCP tool failed: remote_fail");
        assertThat(log.errorMessage()).doesNotContain("raw remote failure");
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

    @Test
    void timesOutSlowMcpToolCallback() {
        ToolCallbackProvider callbackProvider = ToolCallbackProvider.from(new SlowToolCallback());
        ToolGatewayService gateway = new ToolGatewayService(List.of(new McpToolProvider(List.of(callbackProvider),
                new ObjectMapper(), "github", Duration.ofMillis(50))),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_slow"));

        ToolExecutionLog log = gateway.execute("remote_slow", Map.of());

        assertThat(log.succeeded()).isFalse();
        assertThat(log.serverName()).isEqualTo("github");
        assertThat(log.errorCategory()).isEqualTo(ToolExecutionLog.ERROR_REMOTE_TOOL);
        assertThat(log.errorType()).isEqualTo(ToolExecutionLog.ERROR_TYPE_RAW_REMOTE);
        assertThat(log.errorMessage()).contains("timed out");
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

    private static final class EnumToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_enum")
                    .description("Echo an enum argument from a remote MCP server")
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "mode": {"type": "string", "enum": ["read", "write"]}
                              },
                              "required": ["mode"]
                            }
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "mcp:" + toolInput;
        }

    }

    private static final class OneOfToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_one_of")
                    .description("Echo a oneOf argument from a remote MCP server")
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "target": {
                                  "oneOf": [
                                    {"type": "string"},
                                    {"type": "integer"}
                                  ]
                                }
                              },
                              "required": ["target"]
                            }
                            """)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return "mcp:" + toolInput;
        }

    }

    private static final class AnyOfToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_any_of")
                    .description("Echo an anyOf argument from a remote MCP server")
                    .inputSchema("""
                            {
                              "type": "object",
                              "properties": {
                                "target": {
                                  "anyOf": [
                                    {"type": "string"},
                                    {"type": "integer"}
                                  ]
                                }
                              },
                              "required": ["target"]
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

    private static final class SlowToolCallback implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name("remote_slow")
                    .description("Slow remote MCP server tool")
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
            try {
                Thread.sleep(500);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return "late";
        }

    }

}
