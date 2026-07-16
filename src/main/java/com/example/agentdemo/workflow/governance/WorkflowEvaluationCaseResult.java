package com.example.agentdemo.workflow.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkflowEvaluationCaseResult(
        String caseId,
        Map<String, Object> input,
        WorkflowEvaluationCaseStatus status,
        List<String> attemptRunIds,
        List<String> executedPath,
        List<WorkflowEvaluationAssertionResult> assertions,
        Object output,
        String outputSummary,
        WorkflowEvaluationErrorOrigin errorOrigin,
        String errorCode) {

    public WorkflowEvaluationCaseResult {
        input = input == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(input));
        attemptRunIds = attemptRunIds == null ? List.of() : List.copyOf(attemptRunIds);
        executedPath = executedPath == null ? List.of() : List.copyOf(executedPath);
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }

    public WorkflowEvaluationCaseResult(
            String caseId,
            Map<String, Object> input,
            WorkflowEvaluationCaseStatus status,
            List<String> attemptRunIds,
            List<String> executedPath,
            List<WorkflowEvaluationAssertionResult> assertions,
            String outputSummary,
            WorkflowEvaluationErrorOrigin errorOrigin,
            String errorCode) {
        this(caseId, input, status, attemptRunIds, executedPath, assertions,
                outputSummary, outputSummary, errorOrigin, errorCode);
    }
}
