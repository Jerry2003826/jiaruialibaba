package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.StepStatus;

import java.util.List;

public record WorkflowRunGraphNodeView(
        String id,
        String type,
        String label,
        boolean executed,
        StepStatus status,
        String stepId,
        String errorMessage,
        String compositeRole,
        String parallelGroup,
        Integer iterations,
        List<WorkflowRunGraphStepView> children) {

    public WorkflowRunGraphNodeView(String id, String type, String label, boolean executed, StepStatus status,
            String stepId, String errorMessage) {
        this(id, type, label, executed, status, stepId, errorMessage, null, null, null, List.of());
    }

    public WorkflowRunGraphNodeView {
        children = children == null ? List.of() : List.copyOf(children);
    }

}
