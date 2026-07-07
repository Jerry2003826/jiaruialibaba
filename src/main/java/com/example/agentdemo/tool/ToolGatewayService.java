package com.example.agentdemo.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ToolGatewayService {

    private final ToolExecutionPolicy toolExecutionPolicy;
    private final ToolRegistryCache toolRegistryCache;
    private final ToolSchemaValidator toolSchemaValidator;
    private final ToolOutputSanitizer toolOutputSanitizer;

    @Autowired
    public ToolGatewayService(List<ToolProvider> providers, ToolExecutionPolicy toolExecutionPolicy,
            ToolSchemaValidator toolSchemaValidator, ToolOutputSanitizer toolOutputSanitizer) {
        this(toolExecutionPolicy, toolSchemaValidator, toolOutputSanitizer, new ToolRegistryCache(providers));
    }

    public ToolGatewayService(List<ToolProvider> providers) {
        this(providers, new ToolExecutionPolicy());
    }

    public ToolGatewayService(List<ToolProvider> providers, ToolExecutionPolicy toolExecutionPolicy) {
        this(toolExecutionPolicy, new ToolSchemaValidator(new ObjectMapper()),
                new ToolOutputSanitizer(new ObjectMapper(), 16_384),
                new ToolRegistryCache(providers));
    }

    ToolGatewayService(ToolExecutionPolicy toolExecutionPolicy, ToolSchemaValidator toolSchemaValidator,
            ToolRegistryCache toolRegistryCache) {
        this(toolExecutionPolicy, toolSchemaValidator, new ToolOutputSanitizer(new ObjectMapper(), 16_384),
                toolRegistryCache);
    }

    ToolGatewayService(ToolExecutionPolicy toolExecutionPolicy, ToolSchemaValidator toolSchemaValidator,
            ToolOutputSanitizer toolOutputSanitizer, ToolRegistryCache toolRegistryCache) {
        this.toolExecutionPolicy = toolExecutionPolicy;
        this.toolSchemaValidator = toolSchemaValidator;
        this.toolOutputSanitizer = toolOutputSanitizer;
        this.toolRegistryCache = toolRegistryCache;
    }

    public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
        Map<String, Object> safeArguments = safeArguments(arguments);
        ToolProvider provider = toolRegistryCache.findProvider(toolName).orElse(null);
        if (provider == null) {
            return toolNotFound(toolName, safeArguments);
        }
        ToolDescriptor descriptor = toolRegistryCache.find(toolName).orElseGet(() -> descriptor(provider, toolName));
        if (!toolExecutionPolicy.canExecute(descriptor)) {
            return toolNotAllowed(descriptor, safeArguments);
        }
        String validationError = toolSchemaValidator.validateForGateway(descriptor.inputSchema(), safeArguments)
                .orElse(null);
        if (validationError != null) {
            Instant now = Instant.now();
            return ToolExecutionLog.failure(toolName, safeArguments, validationError, now, now, descriptor,
                    ToolExecutionLog.ERROR_VALIDATION);
        }
        ToolExecutionLog log = provider.execute(toolName, safeArguments).withDescriptor(descriptor);
        if (!log.succeeded()) {
            return log;
        }
        return log.withOutput(toolOutputSanitizer.sanitize(log.output()));
    }

    public List<ToolDescriptor> listTools() {
        return toolRegistryCache.list();
    }

    public List<ToolDescriptor> listExecutableTools() {
        return listTools().stream()
                .filter(toolExecutionPolicy::canExecute)
                .toList();
    }

    /** Tool listing enriched with executability (allowlist status) for the console. */
    public List<ToolView> listToolViews() {
        return toolRegistryCache.views(toolExecutionPolicy::canExecute);
    }

    /** Finds a tool descriptor by name (for validation / inspection). */
    public java.util.Optional<ToolDescriptor> findTool(String toolName) {
        return toolRegistryCache.find(toolName);
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
