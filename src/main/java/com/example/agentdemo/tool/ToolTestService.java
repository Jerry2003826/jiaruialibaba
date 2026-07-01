package com.example.agentdemo.tool;

import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Console-only dry-run for tools. Validates arguments against the tool's declared input schema
 * (required fields), executes through {@link ToolGatewayService} (which enforces the allowlist),
 * and records the call to the trace (input/output are redacted by {@code TraceService}).
 */
@Service
public class ToolTestService {

    private final ToolGatewayService toolGatewayService;
    private final TraceService traceService;
    private final ObjectMapper objectMapper;

    public ToolTestService(ToolGatewayService toolGatewayService, TraceService traceService,
            ObjectMapper objectMapper) {
        this.toolGatewayService = toolGatewayService;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
    }

    public ToolExecutionLog test(String toolName, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        TraceRun run = traceService.startRun(RunType.TOOL_CHAT,
                Map.of("toolName", toolName, "arguments", args, "mode", "dry-run"));
        TraceStep step = traceService.startTraceStep(run.runId(), "tool_test_" + toolName,
                Map.of("toolName", toolName, "arguments", args));
        try {
            Optional<String> validationError = validateRequiredArguments(toolName, args);
            ToolExecutionLog log;
            if (validationError.isPresent()) {
                Instant now = Instant.now();
                log = ToolExecutionLog.failure(toolName, args, validationError.get(), now, now, null,
                        ToolExecutionLog.ERROR_VALIDATION);
            }
            else {
                log = toolGatewayService.execute(toolName, args);
            }
            traceService.completeStep(step.stepId(), log);
            traceService.markRunSucceeded(run.runId(), log);
            return log;
        }
        catch (RuntimeException ex) {
            traceService.failStep(step.stepId(), ex);
            traceService.markRunFailed(run.runId(), ex);
            throw ex;
        }
    }

    /**
     * Checks that every field named in the tool's JSON input-schema {@code required} array is
     * present in {@code arguments}. Tools without a JSON object schema (e.g. local tools with an
     * empty schema) rely on provider-level validation and are not blocked here.
     */
    private Optional<String> validateRequiredArguments(String toolName, Map<String, Object> arguments) {
        String schema = toolGatewayService.findTool(toolName).map(ToolDescriptor::inputSchema).orElse(null);
        if (!StringUtils.hasText(schema)) {
            return Optional.empty();
        }
        JsonNode required;
        try {
            required = objectMapper.readTree(schema).path("required");
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return Optional.empty();
        }
        if (!required.isArray()) {
            return Optional.empty();
        }
        List<String> missing = new ArrayList<>();
        required.forEach(node -> {
            String field = node.asText();
            if (StringUtils.hasText(field) && !arguments.containsKey(field)) {
                missing.add(field);
            }
        });
        return missing.isEmpty() ? Optional.empty()
                : Optional.of("Missing required argument(s): " + String.join(", ", missing));
    }

}
