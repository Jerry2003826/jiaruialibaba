package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunStepResponse;
import com.example.agentdemo.workflow.WorkflowCanceledException;
import com.example.agentdemo.workflow.WorkflowExecutionPlan;
import com.example.agentdemo.workflow.WorkflowEvaluationFixtures;
import com.example.agentdemo.workflow.WorkflowRunBudgetRegistry;
import com.example.agentdemo.workflow.WorkflowRuntime;
import com.example.agentdemo.workflow.WorkflowStepSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class WorkflowEvaluationService implements AutoCloseable {

    public static final String SUPPLEMENTAL_CASE_ID = "supplemental-model-input";

    private static final int HARD_MAX_CASES = 12;
    private static final int HARD_MAX_CONCURRENCY = 2;
    private static final int HARD_MAX_QUEUE_CAPACITY = 32;
    private static final int MAX_OUTPUT_SUMMARY_CHARS = 1_000;
    private static final long COORDINATOR_POLL_MS = 5L;
    private static final Set<String> PROVIDER_ERROR_CODES = Set.of(
            "ALIBABA_LLM_UNAVAILABLE",
            "ALIBABA_LLM_NOT_CONFIGURED",
            "REMOTE_TOOL_ERROR");
    private static final Set<String> DESIGN_ERROR_CODES = Set.of(
            "WORKFLOW_VALIDATION_FAILED",
            "WORKFLOW_UNSUPPORTED",
            "WORKFLOW_LLM_OUTPUT_INVALID",
            "WORKFLOW_BUDGET_EXCEEDED",
            "WORKFLOW_RETRY_NOT_ALLOWED",
            "WORKFLOW_TOOL_NOT_ALLOWED",
            WorkflowEvaluationFixtures.ERROR_EVALUATION_TOOL_FAILURE,
            "TOOL_NOT_FOUND",
            "TOOL_NOT_ALLOWED",
            "VALIDATION_ERROR");

    private final WorkflowRunBudgetRegistry budgetRegistry;
    private final WorkflowRuntime workflowRuntime;
    private final TraceService traceService;
    private final WorkflowRuntimeProperties properties;
    private final WorkflowEvaluationAssertionEvaluator assertionEvaluator;
    private final ThreadPoolExecutor executorService;

    public WorkflowEvaluationService(WorkflowRunBudgetRegistry budgetRegistry,
            WorkflowRuntime workflowRuntime,
            TraceService traceService,
            WorkflowRuntimeProperties properties,
            ObjectMapper objectMapper) {
        this.budgetRegistry = budgetRegistry;
        this.workflowRuntime = workflowRuntime;
        this.traceService = traceService;
        this.properties = properties;
        this.assertionEvaluator = new WorkflowEvaluationAssertionEvaluator(objectMapper);
        int concurrency = Math.max(1, Math.min(HARD_MAX_CONCURRENCY,
                properties.getEvaluation().getConcurrency()));
        int queueCapacity = Math.max(1, Math.min(HARD_MAX_QUEUE_CAPACITY,
                properties.getEvaluation().getQueueCapacity()));
        this.executorService = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().name("workflow-evaluation-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public WorkflowEvaluationReport evaluate(WorkflowExecutionPlan candidate,
            List<WorkflowEvaluationCase> activeCases,
            Map<String, Object> supplementalInput) {
        Objects.requireNonNull(candidate, "candidate");
        List<WorkflowEvaluationCase> scheduledCases = selectRuntimeCases(activeCases, supplementalInput);
        if (scheduledCases.isEmpty()) {
            return new WorkflowEvaluationReport(supplementalInput, List.of());
        }

        Authentication capturedAuthentication = SecurityContextHolder.getContext().getAuthentication();
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        List<CaseControl> controls = scheduledCases.stream().map(CaseControl::new).toList();
        List<Future<WorkflowEvaluationCaseResult>> futures = new ArrayList<>(controls.size());
        try {
            for (CaseControl control : controls) {
                futures.add(executorService.submit(() -> callWithContext(
                        capturedAuthentication,
                        capturedMdc,
                        () -> evaluateCase(candidate, control))));
            }
        }
        catch (RejectedExecutionException exception) {
            for (int index = 0; index < futures.size(); index++) {
                cancelWorker(controls.get(index), futures.get(index));
            }
            throw exception;
        }

        long overallDeadlineNanos = deadlineNanos(properties.getEvaluation().getOverallDeadlineMs());
        WorkflowEvaluationCaseResult[] orderedResults = new WorkflowEvaluationCaseResult[scheduledCases.size()];
        int remaining = scheduledCases.size();
        while (remaining > 0) {
            long now = System.nanoTime();
            boolean overallExpired = overallDeadlineNanos != 0L && now - overallDeadlineNanos >= 0L;
            boolean madeProgress = false;
            for (int index = 0; index < futures.size(); index++) {
                if (orderedResults[index] != null) {
                    continue;
                }
                CaseControl control = controls.get(index);
                Future<WorkflowEvaluationCaseResult> future = futures.get(index);
                if (future.isDone()) {
                    orderedResults[index] = completedResult(control, future);
                    remaining--;
                    madeProgress = true;
                    continue;
                }
                boolean caseExpired = control.startedNanos.get() != 0L
                        && elapsedMillis(control.startedNanos.get(), now)
                        >= positiveOrDefault(properties.getEvaluation().getCaseDeadlineMs(), 90_000L);
                if (overallExpired || caseExpired) {
                    cancelWorker(control, future);
                    orderedResults[index] = canceledResult(
                            control,
                            overallExpired ? "EVALUATION_OVERALL_DEADLINE_EXCEEDED"
                                    : "EVALUATION_CASE_DEADLINE_EXCEEDED");
                    remaining--;
                    madeProgress = true;
                    continue;
                }
            }
            if (!madeProgress && remaining > 0) {
                try {
                    Thread.sleep(COORDINATOR_POLL_MS);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    for (int index = 0; index < futures.size(); index++) {
                        if (orderedResults[index] == null) {
                            cancelWorker(controls.get(index), futures.get(index));
                            orderedResults[index] = canceledResult(controls.get(index), "EVALUATION_INTERRUPTED");
                            remaining--;
                        }
                    }
                }
            }
        }
        return new WorkflowEvaluationReport(supplementalInput, List.of(orderedResults));
    }

    private List<WorkflowEvaluationCase> selectRuntimeCases(List<WorkflowEvaluationCase> activeCases,
            Map<String, Object> supplementalInput) {
        int maxCases = Math.max(0, Math.min(HARD_MAX_CASES, properties.getEvaluation().getMaxCases()));
        List<WorkflowEvaluationCase> runtimeCases = activeCases == null ? List.of() : activeCases.stream()
                .filter(Objects::nonNull)
                .filter(workflowCase -> workflowCase.kind() == WorkflowEvaluationCaseKind.RUNTIME)
                .limit(maxCases)
                .toList();
        if (!runtimeCases.isEmpty() || supplementalInput == null || supplementalInput.isEmpty() || maxCases == 0) {
            return runtimeCases;
        }
        Map<String, Object> immutableInput = Map.copyOf(supplementalInput);
        String prompt = String.valueOf(immutableInput.getOrDefault("message", immutableInput));
        WorkflowEvaluationAssertion readableOutput = new WorkflowEvaluationAssertion(
                "supplemental-customer-readable-output",
                WorkflowEvaluationAssertionType.FINAL_OUTPUT_CUSTOMER_READABLE,
                List.of(),
                null,
                null);
        return List.of(new WorkflowEvaluationCase(
                SUPPLEMENTAL_CASE_ID,
                prompt,
                "The supplemental input produces a customer-readable output.",
                WorkflowEvaluationCaseKind.RUNTIME,
                immutableInput,
                List.of(readableOutput)));
    }

    private WorkflowEvaluationCaseResult evaluateCase(WorkflowExecutionPlan candidate, CaseControl control) {
        control.startedNanos.compareAndSet(0L, System.nanoTime());
        List<String> lastPath = List.of();
        String lastOutputSummary = null;
        WorkflowEvaluationErrorOrigin lastOrigin = null;
        String lastErrorCode = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            TraceRun run = null;
            boolean budgetOpened = false;
            try {
                run = traceService.startRun(RunType.WORKFLOW, Map.of(
                        "evaluationCaseId", control.workflowCase.id(),
                        "evaluationAttempt", attempt,
                        "input", control.workflowCase.runtimeInput()));
                control.attemptRunIds.add(run.runId());
                control.currentRunId.set(run.runId());
                budgetRegistry.open(
                        run.runId(),
                        properties.getMaxStepExecutions(),
                        positiveOrDefault(properties.getEvaluation().getCaseDeadlineMs(), 90_000L));
                budgetOpened = true;
                WorkflowRuntime.WorkflowExecutionResult execution = workflowRuntime.run(
                        run.runId(), candidate, executionInput(control.workflowCase));
                List<String> executedPath = execution.steps().stream().map(WorkflowStepSummary::nodeId).toList();
                List<WorkflowEvaluationAssertionResult> assertionResults = assertionEvaluator.evaluate(
                        control.workflowCase.assertions(), execution);
                String outputSummary = assertionEvaluator.outputSummary(execution.output());
                traceService.markRunSucceeded(run.runId(), Map.of(
                        "output", execution.output() == null ? "" : execution.output(),
                        "evaluationAssertions", assertionResults));
                boolean passed = assertionResults.stream()
                        .allMatch(result -> result.status() == WorkflowEvaluationAssertionStatus.PASSED);
                return new WorkflowEvaluationCaseResult(
                        control.workflowCase.id(),
                        control.workflowCase.runtimeInput(),
                        passed ? WorkflowEvaluationCaseStatus.PASSED : WorkflowEvaluationCaseStatus.DESIGN_FAILED,
                        control.attemptRunIds,
                        executedPath,
                        assertionResults,
                        execution.output(),
                        outputSummary,
                        passed ? null : WorkflowEvaluationErrorOrigin.DESIGN,
                        passed ? null : "EVALUATION_ASSERTION_FAILED");
            }
            catch (RuntimeException exception) {
                WorkflowEvaluationErrorOrigin origin = classify(exception);
                String runId = run == null ? null : run.runId();
                List<String> tracedPath = tracePath(runId);
                if (!tracedPath.isEmpty()) {
                    lastPath = tracedPath;
                }
                lastOutputSummary = traceOutputSummary(runId);
                lastOrigin = origin;
                lastErrorCode = errorCode(exception);
                if (runId != null) {
                    markFailedAttempt(runId, origin, exception);
                }
                if (attempt == 1 && isRetryable(origin) && !Thread.currentThread().isInterrupted()) {
                    continue;
                }
                return failedResult(control, lastPath, lastOutputSummary, lastOrigin, lastErrorCode);
            }
            finally {
                if (run != null) {
                    if (budgetOpened) {
                        budgetRegistry.close(run.runId());
                    }
                    control.currentRunId.compareAndSet(run.runId(), null);
                }
            }
        }
        return failedResult(control, lastPath, lastOutputSummary, lastOrigin, lastErrorCode);
    }

    private Map<String, Object> executionInput(WorkflowEvaluationCase workflowCase) {
        WorkflowEvaluationFixture fixture = workflowCase.fixture();
        if (fixture == null || !StringUtils.hasText(fixture.failTool())) {
            return workflowCase.runtimeInput();
        }
        return WorkflowEvaluationFixtures.withToolFailure(workflowCase.runtimeInput(), fixture.failTool());
    }

    private void markFailedAttempt(String runId, WorkflowEvaluationErrorOrigin origin, RuntimeException exception) {
        try {
            if (origin == WorkflowEvaluationErrorOrigin.CANCELED) {
                traceService.markRunCanceled(runId);
            }
            else {
                traceService.markRunFailed(runId, exception);
            }
        }
        catch (RuntimeException ignored) {
            // The original typed failure remains authoritative; trace storage failure is harness evidence.
        }
    }

    private WorkflowEvaluationCaseResult completedResult(CaseControl control,
            Future<WorkflowEvaluationCaseResult> future) {
        try {
            return future.get();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return canceledResult(control, "EVALUATION_INTERRUPTED");
        }
        catch (CancellationException exception) {
            return canceledResult(control, "EVALUATION_CANCELED");
        }
        catch (ExecutionException exception) {
            return new WorkflowEvaluationCaseResult(
                    control.workflowCase.id(),
                    control.workflowCase.runtimeInput(),
                    WorkflowEvaluationCaseStatus.INFRA_ERROR,
                    control.attemptRunIds,
                    tracePath(control.currentRunId.get()),
                    List.of(),
                    null,
                    WorkflowEvaluationErrorOrigin.HARNESS,
                    "EVALUATION_WORKER_FAILED");
        }
    }

    private void cancelWorker(CaseControl control, Future<WorkflowEvaluationCaseResult> future) {
        String runId = control.currentRunId.get();
        if (runId != null) {
            budgetRegistry.cancel(runId);
        }
        future.cancel(true);
    }

    private WorkflowEvaluationCaseResult canceledResult(CaseControl control, String errorCode) {
        String runId = control.currentRunId.get();
        if (runId == null && !control.attemptRunIds.isEmpty()) {
            runId = control.attemptRunIds.getLast();
        }
        return new WorkflowEvaluationCaseResult(
                control.workflowCase.id(),
                control.workflowCase.runtimeInput(),
                WorkflowEvaluationCaseStatus.CANCELED,
                control.attemptRunIds,
                tracePath(runId),
                List.of(),
                traceOutputSummary(runId),
                WorkflowEvaluationErrorOrigin.CANCELED,
                errorCode);
    }

    private WorkflowEvaluationCaseResult failedResult(CaseControl control,
            List<String> executedPath,
            String outputSummary,
            WorkflowEvaluationErrorOrigin origin,
            String errorCode) {
        WorkflowEvaluationErrorOrigin effectiveOrigin = origin == null
                ? WorkflowEvaluationErrorOrigin.HARNESS
                : origin;
        WorkflowEvaluationCaseStatus status = switch (effectiveOrigin) {
            case DESIGN -> WorkflowEvaluationCaseStatus.DESIGN_FAILED;
            case CANCELED -> WorkflowEvaluationCaseStatus.CANCELED;
            case PROVIDER, HARNESS -> WorkflowEvaluationCaseStatus.INFRA_ERROR;
        };
        return new WorkflowEvaluationCaseResult(
                control.workflowCase.id(),
                control.workflowCase.runtimeInput(),
                status,
                control.attemptRunIds,
                executedPath,
                List.of(),
                outputSummary,
                effectiveOrigin,
                errorCode);
    }

    private WorkflowEvaluationErrorOrigin classify(Throwable error) {
        if (hasCause(error, WorkflowCanceledException.class)
                || hasCause(error, InterruptedException.class)
                || error instanceof CancellationException
                || Thread.currentThread().isInterrupted()) {
            return WorkflowEvaluationErrorOrigin.CANCELED;
        }
        BusinessException businessException = firstCause(error, BusinessException.class);
        if (businessException != null) {
            if (PROVIDER_ERROR_CODES.contains(businessException.getCode())) {
                return WorkflowEvaluationErrorOrigin.PROVIDER;
            }
            if (DESIGN_ERROR_CODES.contains(businessException.getCode())) {
                return WorkflowEvaluationErrorOrigin.DESIGN;
            }
        }
        return WorkflowEvaluationErrorOrigin.HARNESS;
    }

    private String errorCode(Throwable error) {
        BusinessException businessException = firstCause(error, BusinessException.class);
        return businessException == null ? error.getClass().getSimpleName() : businessException.getCode();
    }

    private boolean isRetryable(WorkflowEvaluationErrorOrigin origin) {
        return origin == WorkflowEvaluationErrorOrigin.PROVIDER || origin == WorkflowEvaluationErrorOrigin.HARNESS;
    }

    private List<String> tracePath(String runId) {
        if (runId == null) {
            return List.of();
        }
        try {
            return traceService.listSteps(runId).stream()
                    .map(RunStepResponse::nodeName)
                    .map(this::nodeIdFromTraceName)
                    .toList();
        }
        catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private String traceOutputSummary(String runId) {
        if (runId == null) {
            return null;
        }
        try {
            return traceService.listSteps(runId).stream()
                    .map(RunStepResponse::outputJson)
                    .filter(StringUtils::hasText)
                    .reduce((left, right) -> right)
                    .map(this::truncate)
                    .orElse(null);
        }
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private String nodeIdFromTraceName(String nodeName) {
        String prefix = "workflow_node_";
        return nodeName != null && nodeName.startsWith(prefix) ? nodeName.substring(prefix.length()) : nodeName;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_OUTPUT_SUMMARY_CHARS) {
            return value;
        }
        return value.substring(0, MAX_OUTPUT_SUMMARY_CHARS);
    }

    private long deadlineNanos(long deadlineMs) {
        long effectiveDeadlineMs = positiveOrDefault(deadlineMs, 900_000L);
        long durationNanos = TimeUnit.MILLISECONDS.toNanos(effectiveDeadlineMs);
        return System.nanoTime() + durationNanos;
    }

    private long elapsedMillis(long startedNanos, long nowNanos) {
        return TimeUnit.NANOSECONDS.toMillis(nowNanos - startedNanos);
    }

    private long positiveOrDefault(long value, long defaultValue) {
        return value > 0L ? value : defaultValue;
    }

    private <T> T callWithContext(Authentication authentication,
            Map<String, String> capturedMdc,
            java.util.concurrent.Callable<T> callable) throws Exception {
        SecurityContext previousContext = SecurityContextHolder.getContext();
        Map<String, String> previousMdc = MDC.getCopyOfContextMap();
        SecurityContext workerContext = SecurityContextHolder.createEmptyContext();
        workerContext.setAuthentication(authentication);
        try {
            SecurityContextHolder.setContext(workerContext);
            if (capturedMdc == null) {
                MDC.clear();
            }
            else {
                MDC.setContextMap(capturedMdc);
            }
            return callable.call();
        }
        finally {
            SecurityContextHolder.setContext(previousContext);
            if (previousMdc == null) {
                MDC.clear();
            }
            else {
                MDC.setContextMap(previousMdc);
            }
        }
    }

    private <T extends Throwable> boolean hasCause(Throwable error, Class<T> type) {
        return firstCause(error, type) != null;
    }

    private <T extends Throwable> T firstCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = current.getCause();
        }
        return null;
    }

    @Override
    @PreDestroy
    public void close() {
        executorService.shutdownNow().forEach(task -> {
            if (task instanceof Future<?> future) {
                future.cancel(true);
            }
        });
    }

    private static final class CaseControl {

        private final WorkflowEvaluationCase workflowCase;
        private final AtomicLong startedNanos = new AtomicLong();
        private final CopyOnWriteArrayList<String> attemptRunIds = new CopyOnWriteArrayList<>();
        private final AtomicReference<String> currentRunId = new AtomicReference<>();

        private CaseControl(WorkflowEvaluationCase workflowCase) {
            this.workflowCase = workflowCase;
        }
    }

}
