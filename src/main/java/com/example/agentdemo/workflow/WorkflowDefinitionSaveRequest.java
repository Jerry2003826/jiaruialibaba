package com.example.agentdemo.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkflowDefinitionSaveRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        @Valid @NotNull WorkflowDefinition workflowDefinition,
        JsonNode layout,
        @Valid WorkflowVariableSchema variables) {

    /** Compatibility constructor for callers that do not supply layout/variables. */
    public WorkflowDefinitionSaveRequest(String name, String description, WorkflowDefinition workflowDefinition) {
        this(name, description, workflowDefinition, null, null);
    }
}
