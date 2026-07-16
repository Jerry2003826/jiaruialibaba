package com.example.agentdemo.workflow.governance;

import java.util.List;

public record WorkflowEvaluationAssertionResult(
        String assertionId,
        WorkflowEvaluationAssertionType type,
        WorkflowEvaluationAssertionStatus status,
        List<String> expected,
        List<String> actual) {

    public WorkflowEvaluationAssertionResult {
        expected = expected == null ? List.of() : List.copyOf(expected);
        actual = actual == null ? List.of() : List.copyOf(actual);
    }
}
