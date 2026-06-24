package com.example.agentdemo.tool;

import java.util.List;
import java.util.Set;

public record McpServerSummary(
        String name,
        boolean enabled,
        String transport,
        String connectionName,
        String description,
        String commandEnvironmentVariable,
        Set<String> toolsets,
        List<McpRequiredEnvironmentVariable> requiredEnvironmentVariables,
        boolean allowAllRemoteTools,
        Set<String> allowedTools,
        Set<String> rawAllowedToolEntries,
        int registeredToolCount,
        List<String> registeredTools) {
}
