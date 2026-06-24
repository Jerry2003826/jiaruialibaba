package com.example.agentdemo.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McpServerRegistryServiceTest {

    @Test
    void summarizesConfiguredMcpServersWithoutExposingSecretValues() {
        McpServerRegistryProperties properties = new McpServerRegistryProperties();
        McpServerRegistryProperties.Server server = new McpServerRegistryProperties.Server();
        server.setName("github");
        server.setEnabled(true);
        server.setTransport("stdio");
        server.setConnectionName("github");
        server.setDescription("GitHub MCP");
        server.setCommandEnv("GITHUB_MCP_SERVER_COMMAND");
        server.setRequiredEnv(Set.of("DEFINITELY_MISSING_ENV_FOR_TEST"));
        server.setToolsets(Set.of("repos"));
        properties.setServers(List.of(server));
        ToolGatewayService gateway = new ToolGatewayService(List.of(new RemoteToolProvider()));
        McpServerRegistryService service = new McpServerRegistryService(properties,
                ToolExecutionPolicy.allowOnlyRemoteTools("github:get_file_contents", "legacy_tool"),
                gateway);

        List<McpServerSummary> servers = service.listServers();

        assertThat(servers).hasSize(1);
        McpServerSummary summary = servers.getFirst();
        assertThat(summary.name()).isEqualTo("github");
        assertThat(summary.enabled()).isTrue();
        assertThat(summary.transport()).isEqualTo("stdio");
        assertThat(summary.connectionName()).isEqualTo("github");
        assertThat(summary.commandEnvironmentVariable()).isEqualTo("GITHUB_MCP_SERVER_COMMAND");
        assertThat(summary.toolsets()).containsExactly("repos");
        assertThat(summary.allowedTools()).containsExactlyInAnyOrder("get_file_contents", "legacy_tool");
        assertThat(summary.rawAllowedToolEntries()).containsExactlyInAnyOrder(
                "github:get_file_contents", "legacy_tool");
        assertThat(summary.registeredToolCount()).isEqualTo(1);
        assertThat(summary.registeredTools()).containsExactly("get_file_contents");
        assertThat(summary.requiredEnvironmentVariables())
                .containsExactly(new McpRequiredEnvironmentVariable("DEFINITELY_MISSING_ENV_FOR_TEST", false));
    }

    @Test
    void marksAllowAllRemoteToolsWithoutListingSyntheticToolNames() {
        McpServerRegistryProperties properties = new McpServerRegistryProperties();
        McpServerRegistryProperties.Server server = new McpServerRegistryProperties.Server();
        server.setName("github");
        properties.setServers(List.of(server));
        ToolExecutionPolicy policy = new ToolExecutionPolicy();
        policy.setAllowAllRemoteTools(true);
        McpServerRegistryService service = new McpServerRegistryService(properties, policy,
                new ToolGatewayService(List.of(new RemoteToolProvider())));

        McpServerSummary summary = service.listServers().getFirst();

        assertThat(summary.allowAllRemoteTools()).isTrue();
        assertThat(summary.allowedTools()).containsExactly("*");
    }

    private static final class RemoteToolProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "get_file_contents".equals(toolName) || "other_tool".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(
                    new ToolDescriptor("get_file_contents", "Read GitHub file", providerName(), true, "github",
                            "{}"),
                    new ToolDescriptor("other_tool", "Other server tool", providerName(), true, "other", "{}"));
        }

    }

}
