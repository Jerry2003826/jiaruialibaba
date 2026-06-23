package com.example.agentdemo.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record WorkflowRunRequest(
        @Valid @NotNull WorkflowDefinition workflowDefinition,
        @NotNull Map<String, Object> input) {

    public WorkflowRunRequest {
        input = input == null ? Map.of() : Map.copyOf(input);
    }

}
