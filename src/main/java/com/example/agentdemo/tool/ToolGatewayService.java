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
            return toolNotAllowed(toolName, safeArguments);
        }
        return provider.execute(toolName, safeArguments);
    }

    public List<ToolDescriptor> listTools() {
        return providers.stream()
                .flatMap(provider -> provider.tools().stream())
                .toList();
    }

    static ToolExecutionLog toolNotFound(String toolName, Map<String, Object> arguments) {
        Instant now = Instant.now();
        return new ToolExecutionLog(toolName, safeArguments(arguments), null, false,
                "Tool not found: " + toolName, now, now);
    }

    static ToolExecutionLog toolNotAllowed(String toolName, Map<String, Object> arguments) {
        Instant now = Instant.now();
        return new ToolExecutionLog(toolName, safeArguments(arguments), null, false,
                "Remote tool is not allowed by policy: " + toolName, now, now);
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
