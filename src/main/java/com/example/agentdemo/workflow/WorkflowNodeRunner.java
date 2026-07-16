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
        Object retryInput = state.lastOutput();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1 && "http_request".equalsIgnoreCase(node.type())) {
                state.setLastOutput(retryInput);
            }
            Instant startedAt = Instant.now();
            try {
                Object output = executeOnce(runId, node, state, options);
                if (retryableHttpStatus(node, output) && attempt < maxAttempts) {
                    BusinessException retry = new BusinessException("WORKFLOW_HTTP_RETRYABLE_STATUS",
                            "HTTP request returned retryable status " + httpStatus(output));
                    attempts.add(failedAttempt(attempt, startedAt, Instant.now(), retry));
                    continue;
                }
                attempts.add(succeededAttempt(attempt, startedAt, Instant.now()));
                return new WorkflowNodeExecutionResult(output, successTraceOutput(output, attempts, options));
            }
            catch (RuntimeException ex) {
                attempts.add(failedAttempt(attempt, startedAt, Instant.now(), ex));
                if (attempt >= maxAttempts || !shouldRetryFailure(node, ex)) {
                    if (continuedHttpTransportFailure(node, ex)) {
                        Object output = continuedHttpFailureOutput(ex, startedAt);
                        state.setLastOutput(output);
                        return new WorkflowNodeExecutionResult(output, successTraceOutput(output, attempts, options));
                    }
                    throw new WorkflowNodeExecutionFailure(ex, failureTraceOutput(ex, attempts, options),
                            failureSummaryOutput(ex));
                }
            }
        }
        throw new IllegalStateException("Workflow node retry loop exited unexpectedly");
    }

    private boolean retryableHttpStatus(WorkflowNode node, Object output) {
        if (!"http_request".equalsIgnoreCase(node.type()) || !(output instanceof Map<?, ?> map)) {
            return false;
        }
        Object status = map.get("statusCode");
        if (!(status instanceof Number number)) {
            return false;
        }
        return switch (number.intValue()) {
            case 429, 502, 503, 504 -> true;
            default -> false;
        };
    }

    private int httpStatus(Object output) {
        return output instanceof Map<?, ?> map && map.get("statusCode") instanceof Number number
                ? number.intValue() : 0;
    }

    private boolean shouldRetryFailure(WorkflowNode node, RuntimeException ex) {
        if (!"http_request".equalsIgnoreCase(node.type())) {
            return true;
        }
        RuntimeException candidate = ex instanceof WorkflowNodeExecutionFailure failure
                ? failure.original() : ex;
        if (!(candidate instanceof BusinessException businessException)) {
            return true;
        }
        return "WORKFLOW_HTTP_TRANSPORT_ERROR".equals(businessException.getCode())
                || "WORKFLOW_NODE_TIMEOUT".equals(businessException.getCode());
    }

    private boolean continuedHttpTransportFailure(WorkflowNode node, RuntimeException ex) {
        return "http_request".equalsIgnoreCase(node.type())
                && Boolean.TRUE.equals(node.config().get("continueOnError"))
                && shouldRetryFailure(node, ex);
    }

    private Object continuedHttpFailureOutput(RuntimeException ex, Instant startedAt) {
        Map<String, Object> output = orderedMap();
        output.put("statusCode", 0);
        output.put("headers", Map.of());
        output.put("body", "");
        output.put("json", null);
        output.put("durationMs", durationMs(startedAt, Instant.now()));
        output.put("succeeded", false);
        output.put("errorCode", ex instanceof BusinessException businessException
                ? businessException.getCode() : "WORKFLOW_HTTP_TRANSPORT_ERROR");
        output.put("errorMessage", ex.getMessage());
        return output;
    }

    private void validateRetryPolicy(WorkflowNode node, WorkflowNodeRunOptions options) {
        if (options.retryCount() <= 0) {
            return;
        }
        String type = node.type().toLowerCase();
        if (!"tool".equals(type) && !"http_request".equals(type)) {
            return;
        }
        if ("http_request".equals(type)) {
            String method = String.valueOf(node.config().getOrDefault("method", "GET")).toUpperCase();
            if ("GET".equals(method) || "HEAD".equals(method)) {
                return;
            }
        }
        if (!Boolean.TRUE.equals(node.config().get("idempotent"))) {
            throw new BusinessException("WORKFLOW_RETRY_NOT_ALLOWED",
                    node.type() + " node retry requires config.idempotent=true: " + node.id());
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
