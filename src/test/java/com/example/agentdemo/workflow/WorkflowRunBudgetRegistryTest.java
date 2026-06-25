package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the run-level execution budget. A deterministic, injected nano-clock lets the
 * deadline path be asserted without sleeping.
 */
class WorkflowRunBudgetRegistryTest {

    @Test
    void recordStepIsNoOpWhenNoBudgetIsOpen() {
        WorkflowRunBudgetRegistry registry = new WorkflowRunBudgetRegistry();

        assertThatCode(() -> registry.recordStep("unknown-run")).doesNotThrowAnyException();
    }

    @Test
    void stepBudgetAllowsExactlyMaxThenAborts() {
        WorkflowRunBudgetRegistry registry = new WorkflowRunBudgetRegistry();
        registry.open("run-1", 2, 0);

        registry.recordStep("run-1");
        registry.recordStep("run-1");

        assertThatThrownBy(() -> registry.recordStep("run-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_BUDGET_EXCEEDED"));
    }

    @Test
    void nonPositiveStepBudgetIsUnlimited() {
        WorkflowRunBudgetRegistry registry = new WorkflowRunBudgetRegistry();
        registry.open("run-1", 0, 0);

        for (int i = 0; i < 500; i++) {
            registry.recordStep("run-1");
        }

        assertThat(registry.stepsConsumed("run-1")).isEqualTo(500);
    }

    @Test
    void deadlineAbortsOnceTheClockPassesTheBudget() {
        AtomicLong nanoClock = new AtomicLong(0);
        WorkflowRunBudgetRegistry registry = new WorkflowRunBudgetRegistry(nanoClock::get);
        registry.open("run-1", 0, 1000);

        assertThatCode(() -> registry.recordStep("run-1")).doesNotThrowAnyException();

        nanoClock.set(Duration.ofMillis(1500).toNanos());
        assertThatThrownBy(() -> registry.recordStep("run-1"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_DEADLINE_EXCEEDED"));
    }

    @Test
    void closeRemovesBudgetSoLaterStepsAreNoOp() {
        WorkflowRunBudgetRegistry registry = new WorkflowRunBudgetRegistry();
        registry.open("run-1", 1, 0);
        registry.recordStep("run-1");

        registry.close("run-1");

        assertThatCode(() -> registry.recordStep("run-1")).doesNotThrowAnyException();
        assertThat(registry.stepsConsumed("run-1")).isZero();
    }

    @Test
    void budgetsAreIsolatedPerRunId() {
        WorkflowRunBudgetRegistry registry = new WorkflowRunBudgetRegistry();
        registry.open("run-a", 1, 0);
        registry.open("run-b", 5, 0);

        registry.recordStep("run-a");
        assertThatThrownBy(() -> registry.recordStep("run-a"))
                .isInstanceOf(BusinessException.class);

        assertThatCode(() -> {
            registry.recordStep("run-b");
            registry.recordStep("run-b");
        }).doesNotThrowAnyException();
    }

}
