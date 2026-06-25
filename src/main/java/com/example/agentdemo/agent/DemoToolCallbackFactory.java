package com.example.agentdemo.agent;

import com.example.agentdemo.tool.McpToolProvider;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.trace.TraceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DemoToolCallbackFactory {

    private final ToolGatewayService toolGatewayService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<McpToolProvider> mcpToolProvider;

    public DemoToolCallbackFactory(ToolGatewayService toolGatewayService, ObjectMapper objectMapper,
            ObjectProvider<McpToolProvider> mcpToolProvider) {
        this.toolGatewayService = toolGatewayService;
        this.objectMapper = objectMapper;
        this.mcpToolProvider = mcpToolProvider;
    }

    public List<ToolCallback> tracedToolCallbacks(String runId, TraceService traceService,
            List<ToolExecutionLog> toolCalls) {
        List<ToolCallback> callbacks = new ArrayList<>();
        callbacks.add(wrap(buildGetCurrentTimeCallback(), runId, traceService, toolCalls));
        callbacks.add(wrap(buildCalculateCallback(), runId, traceService, toolCalls));
        toolGatewayService.listExecutableTools().stream()
                .filter(ToolDescriptor::remote)
                .map(descriptor -> new GatewayBackedToolCallback(descriptor, toolGatewayService, objectMapper))
                .forEach(callback -> callbacks.add(wrap(callback, runId, traceService, toolCalls)));
        return callbacks;
    }

    private ToolCallback wrap(ToolCallback callback, String runId, TraceService traceService,
            List<ToolExecutionLog> toolCalls) {
        return new TracingToolCallback(callback, runId, traceService, toolCalls);
    }

    private ToolCallback buildGetCurrentTimeCallback() {
        return FunctionToolCallback.builder("getCurrentTime", (Map<String, Object> request) -> execute("getCurrentTime", request))
                .description("Return current server time in ISO-8601 format.")
                .inputType(Map.class)
                .build();
    }

    private ToolCallback buildCalculateCallback() {
        return FunctionToolCallback.builder("calculate", (CalculateRequest request) ->
                        execute("calculate", Map.of("expression", request.expression())))
                .description("Calculate a safe arithmetic expression with +, -, *, / and parentheses.")
                .inputType(CalculateRequest.class)
                .build();
    }

    private String execute(String toolName, Map<String, Object> arguments) {
        ToolExecutionLog log = toolGatewayService.execute(toolName, safeArguments(arguments));
        if (!log.succeeded()) {
            throw new IllegalArgumentException(log.errorMessage());
        }
        return stringifyOutput(log.output());
    }

    private Map<String, Object> safeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(arguments);
    }

    private String stringifyOutput(Object output) {
        if (output == null) {
            return "";
        }
        if (output instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(output);
        }
        catch (Exception ex) {
            return String.valueOf(output);
        }
    }

    public record CalculateRequest(String expression) {
    }

}
