package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cancellation semantics of {@link WorkflowRunBudgetRegistry}: a cancelled run's next node
 * boundary throws {@link WorkflowCanceledException}.
 */
class WorkflowRunBudgetCancelTest {

    @Test
    void recordStepThrowsAfterCancel() {
        WorkflowRunBudgetRegistry registry = new WorkflowRunBudgetRegistry();
        registry.open("run-1", 100, 0);

        assertThatCode(() -> registry.recordStep("run-1")).doesNotThrowAnyException();
        assertThat(registry.cancel("run-1")).isTrue();

        assertThatThrownBy(() -> registry.recordStep("run-1"))
                .isInstanceOf(WorkflowCanceledException.class);
        assertThat(registry.isCanceled("run-1")).isTrue();
    }

    @Test
    void cancelUnknownRunReturnsFalse() {
        WorkflowRunBudgetRegistry registry = new WorkflowRunBudgetRegistry();
        assertThat(registry.cancel("missing")).isFalse();
    }

}
