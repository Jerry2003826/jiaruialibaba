package com.example.agentdemo.workflow;

public record WorkflowRunTraceInput(
        WorkflowRunRequest request,
        String definitionId,
        Integer definitionVersion) {
}
