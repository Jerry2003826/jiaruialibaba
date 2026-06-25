package com.example.agentdemo.agent;

import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.TraceStep;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Wraps a {@link ToolCallback} and records tool execution into run trace steps.
 */
public final class TracingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final String runId;
    private final TraceService traceService;
    private final List<ToolExecutionLog> toolCalls;

    public TracingToolCallback(ToolCallback delegate, String runId, TraceService traceService,
            List<ToolExecutionLog> toolCalls) {
        this.delegate = delegate;
        this.runId = runId;
        this.traceService = traceService;
        this.toolCalls = toolCalls;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        String toolName = delegate.getToolDefinition().name();
        Instant startedAt = Instant.now();
        TraceStep step = traceService.startTraceStep(runId, "tool_" + toolName,
                Map.of("toolName", toolName, "arguments", toolInput));
        try {
            String output = delegate.call(toolInput);
            Instant endedAt = Instant.now();
            ToolExecutionLog log = delegate instanceof GatewayBackedToolCallback gatewayBacked
                    && gatewayBacked.lastExecutionLog() != null
                            ? gatewayBacked.lastExecutionLog()
                            : ToolExecutionLog.success(toolName, toolInput, output, startedAt, endedAt, null);
            toolCalls.add(log);
            traceService.completeStep(step.stepId(), log);
            return output;
        }
        catch (GatewayBackedToolCallback.GatewayToolExecutionException ex) {
            ToolExecutionLog log = ex.log();
            toolCalls.add(log);
            traceService.failStep(step.stepId(), ex, log);
            throw ex;
        }
        catch (RuntimeException ex) {
            Instant endedAt = Instant.now();
            ToolExecutionLog log = ToolExecutionLog.failure(toolName, toolInput, ex.getMessage(), startedAt, endedAt,
                    null, ToolExecutionLog.ERROR_EXECUTION);
            toolCalls.add(log);
            traceService.failStep(step.stepId(), ex, log);
            throw ex;
        }
    }

}
