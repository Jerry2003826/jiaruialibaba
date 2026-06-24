package com.example.agentdemo.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record WorkflowRunRequest(
        @Valid WorkflowDefinition workflowDefinition,
        @Size(max = 64) String definitionId,
        @Positive Integer definitionVersion,
        @NotNull Map<String, Object> input) {

    public WorkflowRunRequest {
        input = input == null ? Map.of() : Map.copyOf(input);
    }

    public WorkflowRunRequest(WorkflowDefinition workflowDefinition, String definitionId, Map<String, Object> input) {
        this(workflowDefinition, definitionId, null, input);
    }

    public WorkflowRunRequest(WorkflowDefinition workflowDefinition, Map<String, Object> input) {
        this(workflowDefinition, null, null, input);
    }

}
