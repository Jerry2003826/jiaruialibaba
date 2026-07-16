package com.example.agentdemo.workflow.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkflowEvaluationReport(
        Map<String, Object> supplementalInput,
        List<WorkflowEvaluationCaseResult> caseResults) {

    public WorkflowEvaluationReport {
        supplementalInput = supplementalInput == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(supplementalInput));
        caseResults = caseResults == null ? List.of() : List.copyOf(caseResults);
    }
}
