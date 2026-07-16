package com.example.agentdemo.workflow;

import java.util.Map;

public record WorkflowGenerationTestResult(
        Map<String, Object> input,
        Object output,
        String runId,
        int stepCount) {

    public WorkflowGenerationTestResult {
        input = input == null ? Map.of() : Map.copyOf(input);
    }

}
