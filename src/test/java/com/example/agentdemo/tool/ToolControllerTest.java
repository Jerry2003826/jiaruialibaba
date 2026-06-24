package com.example.agentdemo.tool;

import com.example.agentdemo.common.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolControllerTest {

    @Test
    void listsRegisteredTools() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LocalToolProvider(new ToolService())));
        ToolController controller = new ToolController(gateway, emptyRegistry(gateway));

        ApiResponse<List<ToolDescriptor>> response = controller.listTools();

        assertThat(response.success()).isTrue();
        assertThat(response.data())
                .extracting(ToolDescriptor::name)
                .contains("getCurrentTime", "calculate");
    }

    @Test
    void listsMcpServers() {
        McpServerRegistryProperties properties = new McpServerRegistryProperties();
        McpServerRegistryProperties.Server server = new McpServerRegistryProperties.Server();
        server.setName("github");
        server.setEnabled(true);
        server.setConnectionName("github");
        properties.setServers(List.of(server));
        ToolGatewayService gateway = new ToolGatewayService(List.of(new RemoteToolProvider()));
        ToolController controller = new ToolController(gateway,
                new McpServerRegistryService(properties, ToolExecutionPolicy.allowOnlyRemoteTools(
                        "github:get_file_contents"), gateway));

        ApiResponse<List<McpServerSummary>> response = controller.listMcpServers();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).hasSize(1);
        assertThat(response.data().getFirst().registeredTools()).containsExactly("get_file_contents");
    }

    private McpServerRegistryService emptyRegistry(ToolGatewayService gateway) {
        return new McpServerRegistryService(new McpServerRegistryProperties(), new ToolExecutionPolicy(), gateway);
    }

    private static final class RemoteToolProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "get_file_contents".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, java.util.Map<String, Object> arguments) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("get_file_contents", "Read file", providerName(), true, "github",
                    "{}"));
        }

    }

}
