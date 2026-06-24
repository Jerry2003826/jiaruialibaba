package com.example.agentdemo.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(100)
@ConditionalOnProperty(prefix = "demo.mcp", name = "enabled", havingValue = "true")
public class McpToolProvider implements ToolProvider {

    private final Map<String, ToolCallback> callbacks;
    private final ObjectMapper objectMapper;

    @Autowired
    public McpToolProvider(ObjectProvider<ToolCallbackProvider> callbackProviders, ObjectMapper objectMapper) {
        this(callbackProviders.orderedStream().toList(), objectMapper);
    }

    McpToolProvider(List<ToolCallbackProvider> callbackProviders) {
        this(callbackProviders, new ObjectMapper());
    }

    McpToolProvider(List<ToolCallbackProvider> callbackProviders, ObjectMapper objectMapper) {
        this.callbacks = indexCallbacks(callbackProviders);
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return "mcp";
    }

    @Override
    public boolean supports(String toolName) {
        return callbacks.containsKey(toolName);
    }

    @Override
    public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
        Instant startedAt = Instant.now();
        Map<String, Object> safeArguments = safeArguments(arguments);
        ToolCallback callback = callbacks.get(toolName);
        if (callback == null) {
            return ToolGatewayService.toolNotFound(toolName, safeArguments);
        }
        try {
            String toolInputJson = objectMapper.writeValueAsString(safeArguments);
            String output = callback.call(toolInputJson);
            return new ToolExecutionLog(toolName, safeArguments, output, true, null, startedAt, Instant.now());
        }
        catch (JsonProcessingException ex) {
            return new ToolExecutionLog(toolName, safeArguments, null, false,
                    "Invalid MCP tool arguments: " + ex.getOriginalMessage(), startedAt, Instant.now());
        }
        catch (RuntimeException ex) {
            return new ToolExecutionLog(toolName, safeArguments, null, false, ex.getMessage(), startedAt, Instant.now());
        }
    }

    @Override
    public List<ToolDescriptor> tools() {
        return callbacks.values().stream()
                .map(callback -> new ToolDescriptor(
                        callback.getToolDefinition().name(),
                        callback.getToolDefinition().description(),
                        providerName(),
                        true))
                .toList();
    }

    private Map<String, ToolCallback> indexCallbacks(List<ToolCallbackProvider> callbackProviders) {
        Map<String, ToolCallback> indexed = new LinkedHashMap<>();
        for (ToolCallbackProvider provider : callbackProviders) {
            for (ToolCallback callback : provider.getToolCallbacks()) {
                indexed.putIfAbsent(callback.getToolDefinition().name(), callback);
            }
        }
        return Map.copyOf(indexed);
    }

    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

}
