package com.example.agentdemo.workflow.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkflowEvaluationCase(
        String id,
        String prompt,
        String expectedBehavior,
        WorkflowEvaluationCaseKind kind,
        Map<String, Object> runtimeInput,
        List<WorkflowEvaluationAssertion> assertions,
        WorkflowEvaluationFixture fixture) {

    public WorkflowEvaluationCase {
        kind = kind == null ? WorkflowEvaluationCaseKind.STATIC : kind;
        runtimeInput = runtimeInput == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(runtimeInput));
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }

    public WorkflowEvaluationCase(String id, String prompt, String expectedBehavior) {
        this(id, prompt, expectedBehavior, WorkflowEvaluationCaseKind.STATIC, Map.of(), List.of(), null);
    }

    public WorkflowEvaluationCase(String id, String prompt, String expectedBehavior,
            WorkflowEvaluationCaseKind kind, Map<String, Object> runtimeInput,
            List<WorkflowEvaluationAssertion> assertions) {
        this(id, prompt, expectedBehavior, kind, runtimeInput, assertions, null);
    }
}
