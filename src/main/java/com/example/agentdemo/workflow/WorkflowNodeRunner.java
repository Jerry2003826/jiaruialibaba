package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class WorkflowNodeRunner {

    private final WorkflowNodeExecutor nodeExecutor;
    private final ExecutorService executorService;

    WorkflowNodeRunner(WorkflowNodeExecutor nodeExecutor, ExecutorService executorService) {
        this.nodeExecutor = nodeExecutor;
        this.executorService = executorService;
    }

    WorkflowNodeExecutionResult execute(String runId, WorkflowNode node, WorkflowExecutionState state) {
        WorkflowNodeRunOptions options = WorkflowNodeRunOptions.from(node);
        validateRetryPolicy(node, options);
        List<Map<String, Object>> attempts = new ArrayList<>();
        int maxAttempts = options.retryCount() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Instant startedAt = Instant.now();
            try {
                Object output = executeOnce(runId, node, state, options);
                attempts.add(succeededAttempt(attempt, startedAt, Instant.now()));
                return new WorkflowNodeExecutionResult(output, successTraceOutput(output, attempts, options));
            }
            catch (RuntimeException ex) {
                attempts.add(failedAttempt(attempt, startedAt, Instant.now(), ex));
                if (attempt >= maxAttempts) {
                    throw new WorkflowNodeExecutionFailure(ex, failureTraceOutput(ex, attempts, options),
                            failureSummaryOutput(ex));
                }
            }
        }
        throw new IllegalStateException("Workflow node retry loop exited unexpectedly");
    }

    private void validateRetryPolicy(WorkflowNode node, WorkflowNodeRunOptions options) {
        if (options.retryCount() <= 0 || !"tool".equalsIgnoreCase(node.type())) {
            return;
        }
        if (!Boolean.TRUE.equals(node.config().get("idempotent"))) {
            throw new BusinessException("WORKFLOW_RETRY_NOT_ALLOWED",
                    "Tool node retry requires config.idempotent=true: " + node.id());
        }
    }

    private Object executeOnce(String runId, WorkflowNode node, WorkflowExecutionState state,
            WorkflowNodeRunOptions options) {
        if (options.timeoutMs() <= 0) {
            return nodeExecutor.execute(runId, node, state);
        }
        WorkflowInlineExecutionService.InlineExecutionContext inlineContext =
                nodeExecutor.inlineExecutionService().captureContext();
        Future<Object> future = executorService.submit(() ->
                inlineContext == null
                        ? nodeExecutor.execute(runId, node, state)
                        : nodeExecutor.inlineExecutionService().callWithContext(inlineContext,
                                () -> nodeExecutor.execute(runId, node, state)));
        try {
            return future.get(options.timeoutMs(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex) {
            future.cancel(true);
            throw new BusinessException("WORKFLOW_NODE_TIMEOUT",
                    "Workflow node timed out after " + options.timeoutMs() + " ms: " + node.id(), ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new BusinessException("WORKFLOW_INTERRUPTED", "Workflow node execution was interrupted: "
                    + node.id(), ex);
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new BusinessException("WORKFLOW_NODE_FAILED", "Workflow node failed: " + node.id(), ex);
        }
    }

    private Object successTraceOutput(Object output, List<Map<String, Object>> attempts,
            WorkflowNodeRunOptions options) {
        if (!options.retryOrTimeoutConfigured() && attempts.size() == 1) {
            return output;
        }
        Map<String, Object> traceOutput = orderedMap();
        traceOutput.put("output", output);
        traceOutput.put("attempts", attempts);
        traceOutput.put("retryCount", options.retryCount());
        traceOutput.put("timeoutMs", options.timeoutMs());
        return traceOutput;
    }

    private Object failureTraceOutput(RuntimeException ex, List<Map<String, Object>> attempts,
            WorkflowNodeRunOptions options) {
        Object failureOutput = failureSummaryOutput(ex);
        if (!options.retryOrTimeoutConfigured() && attempts.size() == 1) {
            return failureOutput;
        }
        Map<String, Object> traceOutput = orderedMap();
        traceOutput.put("output", failureOutput);
        traceOutput.put("attempts", attempts);
        traceOutput.put("retryCount", options.retryCount());
        traceOutput.put("timeoutMs", options.timeoutMs());
        return traceOutput;
    }

    private Object failureSummaryOutput(RuntimeException ex) {
        if (ex instanceof WorkflowNodeExecutionException nodeException) {
            return nodeException.output();
        }
        return nodeExecutor.errorOutput(ex);
    }

    private Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    private Map<String, Object> succeededAttempt(int attempt, Instant startedAt, Instant endedAt) {
        Map<String, Object> output = orderedMap();
        output.put("attempt", attempt);
        output.put("status", "SUCCEEDED");
        output.put("durationMs", durationMs(startedAt, endedAt));
        return output;
    }

    private Map<String, Object> failedAttempt(int attempt, Instant startedAt, Instant endedAt, RuntimeException ex) {
        Map<String, Object> output = orderedMap();
        output.put("attempt", attempt);
        output.put("status", "FAILED");
        output.put("errorType", ex.getClass().getSimpleName());
        output.put("errorMessage", ex.getMessage());
        output.put("durationMs", durationMs(startedAt, endedAt));
        return output;
    }

    private long durationMs(Instant startedAt, Instant endedAt) {
        return Math.max(0, Duration.between(startedAt, endedAt).toMillis());
    }

}
