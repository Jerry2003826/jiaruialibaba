package com.example.agentdemo.tool;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ToolGatewayService {

    private final List<ToolProvider> providers;
    private final ToolExecutionPolicy toolExecutionPolicy;

    @Autowired
    public ToolGatewayService(List<ToolProvider> providers, ToolExecutionPolicy toolExecutionPolicy) {
        this.providers = List.copyOf(providers);
        this.toolExecutionPolicy = toolExecutionPolicy;
    }

    public ToolGatewayService(List<ToolProvider> providers) {
        this(providers, new ToolExecutionPolicy());
    }

    public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = safeArguments(arguments);
        ToolProvider provider = providers.stream()
                .filter(candidate -> candidate.supports(toolName))
                .findFirst()
                .orElse(null);
        if (provider == null) {
            return toolNotFound(toolName, safeArguments);
        }
        ToolDescriptor descriptor = descriptor(provider, toolName);
        if (!toolExecutionPolicy.canExecute(descriptor)) {
            return toolNotAllowed(descriptor, safeArguments);
        }
        return provider.execute(toolName, safeArguments).withDescriptor(descriptor);
    }

    public List<ToolDescriptor> listTools() {
        return providers.stream()
                .flatMap(provider -> provider.tools().stream())
                .toList();
    }

    public List<ToolDescriptor> listExecutableTools() {
        return listTools().stream()
                .filter(toolExecutionPolicy::canExecute)
                .toList();
    }

    static ToolExecutionLog toolNotFound(String toolName, Map<String, Object> arguments) {
        Instant now = Instant.now();
        return ToolExecutionLog.failure(toolName, safeArguments(arguments), "Tool not found: " + toolName,
                now, now, null, ToolExecutionLog.ERROR_TOOL_NOT_FOUND);
    }

    static ToolExecutionLog toolNotAllowed(String toolName, Map<String, Object> arguments) {
        return toolNotAllowed(new ToolDescriptor(toolName, "", "mcp", true), arguments);
    }

    static ToolExecutionLog toolNotAllowed(ToolDescriptor descriptor, Map<String, Object> arguments) {
        Instant now = Instant.now();
        return ToolExecutionLog.failure(descriptor.name(), safeArguments(arguments),
                "Remote tool is not allowed by policy: " + descriptor.name(), now, now, descriptor,
                ToolExecutionLog.ERROR_TOOL_NOT_ALLOWED);
    }

    private ToolDescriptor descriptor(ToolProvider provider, String toolName) {
        return provider.tools().stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst()
                .orElseGet(() -> new ToolDescriptor(toolName, "", provider.providerName(),
                        !"local".equals(provider.providerName())));
    }

    private static Map<String, Object> safeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

}
