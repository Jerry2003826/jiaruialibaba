package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Tracks a per-run execution budget (total node executions and an optional wall-clock deadline) keyed
 * by {@code runId}.
 *
 * <p>Per-loop {@code maxIterations} and per-node {@code timeoutMs} bound individual steps, but nothing
 * bounded a whole run: nested loops, dynamic fan-out and subgraphs can multiply into an unbounded
 * number of node executions or run for an unbounded time. This registry adds that run-level guard.
 *
 * <p>A budget is {@link #open opened} once at the run entry point ({@code WorkflowService.run}) and
 * {@link #close closed} when the run finishes. Every node execution funnels through
 * {@link WorkflowNodeTraceExecutor#execute} which calls {@link #recordStep}; because nested subgraphs
 * reuse the parent {@code runId}, their steps are charged to the same budget. {@code recordStep} is a
 * no-op for a {@code runId} that has no open budget, so callers that bypass the service entry point
 * (such as focused unit tests) are unaffected.
 */
@Component
public class WorkflowRunBudgetRegistry {

    private final ConcurrentHashMap<String, RunBudget> budgets = new ConcurrentHashMap<>();
    private final LongSupplier nanoClock;

    public WorkflowRunBudgetRegistry() {
        this(System::nanoTime);
    }

    WorkflowRunBudgetRegistry(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    /**
     * Starts tracking a budget for {@code runId}. A non-positive {@code maxStepExecutions} disables the
     * step guard; a non-positive {@code deadlineMs} disables the deadline guard.
     */
    public void open(String runId, int maxStepExecutions, long deadlineMs) {
        long deadlineNanos = deadlineMs > 0 ? nanoClock.getAsLong() + Duration.ofMillis(deadlineMs).toNanos() : 0L;
        budgets.put(runId, new RunBudget(maxStepExecutions, deadlineNanos));
    }

    /**
     * Requests best-effort cancellation of an in-flight run. The next node boundary
     * ({@link #recordStep}) throws {@link WorkflowCanceledException}. Returns {@code true} when a
     * budget was open for {@code runId} (i.e. the run was active), {@code false} otherwise.
     */
    public boolean cancel(String runId) {
        RunBudget budget = budgets.get(runId);
        if (budget == null) {
            return false;
        }
        budget.canceled.set(true);
        return true;
    }

    boolean isCanceled(String runId) {
        RunBudget budget = budgets.get(runId);
        return budget != null && budget.canceled.get();
    }

    /**
     * Charges one node execution to {@code runId}'s budget, throwing if the step or time budget is
     * exhausted. No-op when no budget is open for {@code runId}.
     */
    public void recordStep(String runId) {
        RunBudget budget = budgets.get(runId);
        if (budget != null) {
            budget.recordStep(runId, nanoClock.getAsLong());
        }
    }

    public void close(String runId) {
        budgets.remove(runId);
    }

    int stepsConsumed(String runId) {
        RunBudget budget = budgets.get(runId);
        return budget == null ? 0 : budget.steps.get();
    }

    private static final class RunBudget {

        private final int maxStepExecutions;
        private final long deadlineNanos;
        private final AtomicInteger steps = new AtomicInteger();
        private final java.util.concurrent.atomic.AtomicBoolean canceled =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private RunBudget(int maxStepExecutions, long deadlineNanos) {
            this.maxStepExecutions = maxStepExecutions;
            this.deadlineNanos = deadlineNanos;
        }

        private void recordStep(String runId, long nowNanos) {
            if (canceled.get()) {
                throw new WorkflowCanceledException(runId);
            }
            // Overflow-safe deadline comparison (System.nanoTime values can wrap).
            if (deadlineNanos != 0L && nowNanos - deadlineNanos >= 0L) {
                throw new BusinessException("WORKFLOW_DEADLINE_EXCEEDED",
                        "Workflow run " + runId + " exceeded its time budget");
            }
            int consumed = steps.incrementAndGet();
            if (maxStepExecutions > 0 && consumed > maxStepExecutions) {
                throw new BusinessException("WORKFLOW_BUDGET_EXCEEDED",
                        "Workflow run " + runId + " exceeded its step budget of " + maxStepExecutions
                                + " node executions");
            }
        }

    }

}
