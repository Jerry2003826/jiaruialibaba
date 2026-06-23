package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowRunResponse(Object output, String runId, List<WorkflowStepSummary> steps) {
}
