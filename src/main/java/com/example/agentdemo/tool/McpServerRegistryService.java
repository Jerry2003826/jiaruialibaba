package com.example.agentdemo.tool;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class McpServerRegistryService {

    private static final String ALLOW_ALL_MARKER = "*";

    private final McpServerRegistryProperties properties;
    private final ToolExecutionPolicy toolExecutionPolicy;
    private final ToolGatewayService toolGatewayService;

    public McpServerRegistryService(McpServerRegistryProperties properties, ToolExecutionPolicy toolExecutionPolicy,
            ToolGatewayService toolGatewayService) {
        this.properties = properties;
        this.toolExecutionPolicy = toolExecutionPolicy;
        this.toolGatewayService = toolGatewayService;
    }

    public List<McpServerSummary> listServers() {
        List<ToolDescriptor> tools = toolGatewayService.listTools();
        return properties.getServers().stream()
                .filter(Objects::nonNull)
                .filter(server -> StringUtils.hasText(server.getName()))
                .map(server -> summarize(server, tools))
                .toList();
    }

    private McpServerSummary summarize(McpServerRegistryProperties.Server server, List<ToolDescriptor> tools) {
        List<String> registeredTools = tools.stream()
                .filter(ToolDescriptor::remote)
                .filter(tool -> server.getName().equals(tool.serverName()))
                .map(ToolDescriptor::name)
                .sorted()
                .toList();
        return new McpServerSummary(
                server.getName(),
                server.isEnabled(),
                server.getTransport(),
                server.getConnectionName(),
                server.getDescription(),
                server.getCommandEnv(),
                Set.copyOf(server.getToolsets()),
                requiredEnvironmentVariables(server),
                toolExecutionPolicy.isAllowAllRemoteTools(),
                allowedToolsFor(server.getName()),
                Set.copyOf(toolExecutionPolicy.getAllowedRemoteTools()),
                registeredTools.size(),
                registeredTools);
    }

    private List<McpRequiredEnvironmentVariable> requiredEnvironmentVariables(McpServerRegistryProperties.Server server) {
        return server.getRequiredEnv().stream()
                .map(name -> new McpRequiredEnvironmentVariable(name, StringUtils.hasText(System.getenv(name))))
                .toList();
    }

    private Set<String> allowedToolsFor(String serverName) {
        Set<String> allowedTools = new LinkedHashSet<>();
        if (toolExecutionPolicy.isAllowAllRemoteTools()) {
            allowedTools.add(ALLOW_ALL_MARKER);
            return allowedTools;
        }
        String prefix = serverName + ":";
        for (String entry : toolExecutionPolicy.getAllowedRemoteTools()) {
            if (entry.startsWith(prefix)) {
                allowedTools.add(entry.substring(prefix.length()));
            }
            else if (!entry.contains(":")) {
                allowedTools.add(entry);
            }
        }
        return allowedTools;
    }

}
