package com.example.agentdemo.workflow.governance;

import java.util.List;

public record WorkflowEvaluationAssertion(
        String id,
        WorkflowEvaluationAssertionType type,
        List<String> values,
        String field,
        Object expectedValue) {

    public WorkflowEvaluationAssertion {
        values = values == null ? List.of() : List.copyOf(values);
    }
}
