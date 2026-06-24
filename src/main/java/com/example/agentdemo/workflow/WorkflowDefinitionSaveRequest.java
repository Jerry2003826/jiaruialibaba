package com.example.agentdemo.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record WorkflowDefinitionSaveRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 512) String description,
        @Valid @NotNull WorkflowDefinition workflowDefinition) {
}
