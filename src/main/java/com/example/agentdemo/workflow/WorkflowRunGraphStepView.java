package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.StepStatus;

public record WorkflowRunGraphStepView(
        String id,
        String type,
        StepStatus status,
        String stepId,
        String errorMessage) {
}
