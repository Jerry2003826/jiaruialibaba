package com.example.agentdemo.agent;

import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

final class GatewayBackedToolCallback implements ToolCallback {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ToolDefinition definition;
    private final ToolGatewayService gateway;
    private final ObjectMapper objectMapper;
    private ToolExecutionLog lastExecutionLog;

    GatewayBackedToolCallback(ToolDescriptor descriptor, ToolGatewayService gateway, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.objectMapper = objectMapper;
        this.definition = ToolDefinition.builder()
                .name(descriptor.name())
                .description(descriptor.description())
                .inputSchema(descriptor.inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        Map<String, Object> arguments = parseArguments(toolInput);
        ToolExecutionLog log = gateway.execute(definition.name(), arguments);
        this.lastExecutionLog = log;
        if (!log.succeeded()) {
            throw new GatewayToolExecutionException(log);
        }
        return stringify(log.output());
    }

    ToolExecutionLog lastExecutionLog() {
        return lastExecutionLog;
    }

    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> arguments = objectMapper.readValue(toolInput, MAP_TYPE);
            return arguments == null ? Map.of() : arguments;
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid tool argument JSON: " + ex.getOriginalMessage(), ex);
        }
    }

    private String stringify(Object output) {
        if (output == null) {
            return "";
        }
        if (output instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(output);
        }
        catch (JsonProcessingException ex) {
            return String.valueOf(output);
        }
    }

    static final class GatewayToolExecutionException extends RuntimeException {

        private final ToolExecutionLog log;

        GatewayToolExecutionException(ToolExecutionLog log) {
            super(log.errorMessage());
            this.log = log;
        }

        ToolExecutionLog log() {
            return log;
        }

    }

}
