package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowRunResponse(
        Object output,
        String runId,
        List<WorkflowStepSummary> steps,
        String definitionId,
        Integer definitionVersion) {

    public WorkflowRunResponse(Object output, String runId, List<WorkflowStepSummary> steps) {
        this(output, runId, steps, null, null);
    }

}
