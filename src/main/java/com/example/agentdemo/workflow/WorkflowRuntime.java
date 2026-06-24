package com.example.agentdemo.workflow;

import java.util.List;
import java.util.Map;

public interface WorkflowRuntime {

    WorkflowExecutionResult run(String runId, WorkflowExecutionPlan executionPlan, Map<String, Object> input);

    record WorkflowExecutionResult(Object output, List<WorkflowStepSummary> steps) {
    }

}
