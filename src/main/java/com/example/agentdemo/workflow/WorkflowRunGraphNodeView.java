package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.StepStatus;

public record WorkflowRunGraphNodeView(
        String id,
        String type,
        String label,
        boolean executed,
        StepStatus status,
        String stepId,
        String errorMessage) {
}
