package com.example.agentdemo.tool;

import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Console-only dry-run for tools. Validates arguments against the tool's declared input schema,
 * executes through {@link ToolGatewayService} (which enforces the allowlist), and records the call
 * to the trace (input/output are redacted by {@code TraceService}).
 */
@Service
public class ToolTestService {

    private final ToolGatewayService toolGatewayService;
    private final TraceService traceService;
    private final ToolSchemaValidator toolSchemaValidator;

    public ToolTestService(ToolGatewayService toolGatewayService, TraceService traceService,
            ToolSchemaValidator toolSchemaValidator) {
        this.toolGatewayService = toolGatewayService;
        this.traceService = traceService;
        this.toolSchemaValidator = toolSchemaValidator;
    }

    public ToolExecutionLog test(String toolName, Map<String, Object> arguments) {
        Map<String, Object> args = arguments == null ? Map.of() : arguments;
        TraceRun run = traceService.startRun(RunType.TOOL_CHAT,
                Map.of("toolName", toolName, "arguments", args, "mode", "dry-run"));
        TraceStep step = traceService.startTraceStep(run.runId(), "tool_test_" + toolName,
                Map.of("toolName", toolName, "arguments", args));
        try {
            Optional<String> validationError = validateArguments(toolName, args);
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
     * Reuses the shared schema validator so dry-run mode matches gateway validation without
     * duplicating JSON Schema parsing rules.
     */
    private Optional<String> validateArguments(String toolName, Map<String, Object> arguments) {
        return toolGatewayService.findTool(toolName)
                .flatMap(descriptor -> toolSchemaValidator.validateForToolTest(descriptor.inputSchema(), arguments));
    }

}
