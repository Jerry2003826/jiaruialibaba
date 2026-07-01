package com.example.agentdemo.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Order(100)
@ConditionalOnProperty(prefix = "demo.mcp", name = "enabled", havingValue = "true")
public class McpToolProvider implements ToolProvider {

    private final Map<String, ToolCallback> callbacks;
    private final ObjectMapper objectMapper;
    private final String serverName;
    private final Duration callTimeout;

    @Autowired
    public McpToolProvider(ObjectProvider<ToolCallbackProvider> callbackProviders, ObjectMapper objectMapper,
            @Value("${demo.mcp.server-name:mcp}") String serverName,
            @Value("${demo.mcp.tool-timeout-ms:30000}") long toolTimeoutMs) {
        this(callbackProviders.orderedStream().toList(), objectMapper, serverName, Duration.ofMillis(toolTimeoutMs));
    }

    McpToolProvider(List<ToolCallbackProvider> callbackProviders) {
        this(callbackProviders, new ObjectMapper(), "mcp");
    }

    McpToolProvider(List<ToolCallbackProvider> callbackProviders, String serverName) {
        this(callbackProviders, new ObjectMapper(), serverName);
    }

    McpToolProvider(List<ToolCallbackProvider> callbackProviders, ObjectMapper objectMapper) {
        this(callbackProviders, objectMapper, "mcp");
    }

    McpToolProvider(List<ToolCallbackProvider> callbackProviders, ObjectMapper objectMapper, String serverName) {
        this(callbackProviders, objectMapper, serverName, Duration.ofSeconds(30));
    }

    McpToolProvider(List<ToolCallbackProvider> callbackProviders, ObjectMapper objectMapper, String serverName,
            Duration callTimeout) {
        this.callbacks = indexCallbacks(callbackProviders);
        this.objectMapper = objectMapper;
        this.serverName = StringUtils.hasText(serverName) ? serverName.trim() : providerName();
        this.callTimeout = callTimeout == null ? Duration.ofSeconds(30) : callTimeout;
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
        ToolDescriptor descriptor = descriptor(callback);
        String validationError = validateArguments(callback, safeArguments);
        if (validationError != null) {
            return ToolExecutionLog.failure(toolName, safeArguments, validationError, startedAt, Instant.now(),
                    descriptor, ToolExecutionLog.ERROR_VALIDATION);
        }
        try {
            String toolInputJson = objectMapper.writeValueAsString(safeArguments);
            String output = callWithTimeout(callback, toolInputJson);
            return ToolExecutionLog.success(toolName, safeArguments, output, startedAt, Instant.now(), descriptor);
        }
        catch (JsonProcessingException ex) {
            return ToolExecutionLog.failure(toolName, safeArguments,
                    "Invalid MCP tool arguments: " + ex.getOriginalMessage(), startedAt, Instant.now(),
                    descriptor, ToolExecutionLog.ERROR_SERIALIZATION);
        }
        catch (RuntimeException ex) {
            return ToolExecutionLog.failure(toolName, safeArguments, remoteErrorMessage(toolName, ex), startedAt, Instant.now(),
                    descriptor, ToolExecutionLog.ERROR_REMOTE_TOOL, ToolExecutionLog.ERROR_TYPE_RAW_REMOTE);
        }
    }

    private String callWithTimeout(ToolCallback callback, String toolInputJson) {
        if (callTimeout.isZero() || callTimeout.isNegative()) {
            return callback.call(toolInputJson);
        }
        String toolName = callback.getToolDefinition().name();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "mcp-tool-call-" + toolName);
            thread.setDaemon(true);
            return thread;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor(threadFactory);
        Future<String> result = executor.submit(() -> callback.call(toolInputJson));
        try {
            return result.get(Math.max(1L, callTimeout.toMillis()), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex) {
            result.cancel(true);
            throw new McpToolTimeoutException(toolName, callTimeout, ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result.cancel(true);
            throw new McpToolTimeoutException(toolName, callTimeout, ex);
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Remote MCP tool failed: " + toolName, cause);
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Override
    public List<ToolDescriptor> tools() {
        return callbacks.values().stream()
                .map(this::descriptor)
                .toList();
    }

    /**
     * Exposes raw MCP tool callbacks for LLM tool-calling integration.
     */
    public List<ToolCallback> exposedToolCallbacks() {
        return callbacks.values().stream().toList();
    }

    private ToolDescriptor descriptor(ToolCallback callback) {
        return new ToolDescriptor(
                callback.getToolDefinition().name(),
                callback.getToolDefinition().description(),
                providerName(),
                true,
                serverName,
                callback.getToolDefinition().inputSchema());
    }

    private String validateArguments(ToolCallback callback, Map<String, Object> arguments) {
        String inputSchema = callback.getToolDefinition().inputSchema();
        if (inputSchema == null || inputSchema.isBlank()) {
            return null;
        }
        JsonNode schema;
        try {
            schema = objectMapper.readTree(inputSchema);
        }
        catch (JsonProcessingException ex) {
            return "Invalid MCP tool input schema: " + ex.getOriginalMessage();
        }
        String requiredError = validateRequiredArguments(schema, arguments);
        if (requiredError != null) {
            return requiredError;
        }
        return validateArgumentTypes(schema, arguments);
    }

    private String validateRequiredArguments(JsonNode schema, Map<String, Object> arguments) {
        JsonNode required = schema.path("required");
        if (!required.isArray()) {
            return null;
        }
        for (JsonNode requiredField : required) {
            String fieldName = requiredField.asText("");
            if (!fieldName.isBlank() && !arguments.containsKey(fieldName)) {
                return "Missing required MCP tool argument: " + fieldName;
            }
        }
        return null;
    }

    private String validateArgumentTypes(JsonNode schema, Map<String, Object> arguments) {
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return null;
        }
        for (Map.Entry<String, Object> argument : arguments.entrySet()) {
            JsonNode property = properties.path(argument.getKey());
            if (property.isMissingNode()) {
                continue;
            }
            String validationError = validateValue(argument.getKey(), property, argument.getValue());
            if (validationError != null) {
                return validationError;
            }
        }
        return null;
    }

    private String validateValue(String argumentName, JsonNode schema, Object value) {
        String compositionError = validateCompositions(argumentName, schema, value);
        if (compositionError != null) {
            return compositionError;
        }
        JsonNode typeNode = schema.path("type");
        if (value == null) {
            if (!typeNode.isMissingNode() && !allowsNull(typeNode)) {
                String expectedType = firstType(typeNode);
                return "MCP tool argument " + argumentName + " must be "
                        + (expectedType == null ? "non-null" : expectedType);
            }
            return validateEnum(argumentName, schema, value);
        }
        if (!matchesAnyType(value, typeNode)) {
            String expectedType = firstType(typeNode);
            return "MCP tool argument " + argumentName + " must be "
                    + (expectedType == null ? "a supported JSON Schema type" : expectedType);
        }
        return validateEnum(argumentName, schema, value);
    }

    private String validateCompositions(String argumentName, JsonNode schema, Object value) {
        JsonNode oneOf = schema.path("oneOf");
        if (oneOf.isArray()) {
            int matches = matchingSchemaCount(argumentName, oneOf, value);
            if (matches != 1) {
                return "MCP tool argument " + argumentName + " must match exactly one oneOf schema";
            }
        }
        JsonNode anyOf = schema.path("anyOf");
        if (anyOf.isArray() && matchingSchemaCount(argumentName, anyOf, value) == 0) {
            return "MCP tool argument " + argumentName + " must match anyOf schema";
        }
        return null;
    }

    private int matchingSchemaCount(String argumentName, JsonNode candidateSchemas, Object value) {
        int matches = 0;
        for (JsonNode candidateSchema : candidateSchemas) {
            if (validateValue(argumentName, candidateSchema, value) == null) {
                matches++;
            }
        }
        return matches;
    }

    private String validateEnum(String argumentName, JsonNode schema, Object value) {
        JsonNode enumValues = schema.path("enum");
        if (!enumValues.isArray()) {
            return null;
        }
        JsonNode actualValue = objectMapper.valueToTree(value);
        for (JsonNode enumValue : enumValues) {
            if (enumValue.equals(actualValue)) {
                return null;
            }
        }
        return "MCP tool argument " + argumentName + " must be one of " + enumValues;
    }

    private String remoteErrorMessage(String toolName, RuntimeException ex) {
        if (ex instanceof McpToolTimeoutException) {
            return ex.getMessage();
        }
        return "Remote MCP tool failed: " + toolName;
    }

    private String firstType(JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return typeNode.asText();
        }
        if (typeNode.isArray()) {
            for (JsonNode candidate : typeNode) {
                if (!"null".equals(candidate.asText())) {
                    return candidate.asText();
                }
            }
        }
        return null;
    }

    private boolean allowsNull(JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return "null".equals(typeNode.asText());
        }
        if (typeNode.isArray()) {
            for (JsonNode candidate : typeNode) {
                if ("null".equals(candidate.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesType(Object value, String expectedType) {
        return switch (expectedType) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof Collection<?> || value.getClass().isArray();
            case "null" -> value == null;
            default -> true;
        };
    }

    private boolean matchesAnyType(Object value, JsonNode typeNode) {
        if (typeNode.isTextual()) {
            return matchesType(value, typeNode.asText());
        }
        if (typeNode.isArray()) {
            for (JsonNode candidate : typeNode) {
                String expectedType = candidate.asText();
                if (!"null".equals(expectedType) && matchesType(value, expectedType)) {
                    return true;
                }
            }
            return false;
        }
        return true;
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

    private static final class McpToolTimeoutException extends RuntimeException {

        private McpToolTimeoutException(String toolName, Duration timeout, Throwable cause) {
            super("Remote MCP tool timed out after " + timeout.toMillis() + " ms: " + toolName, cause);
        }

    }

}
