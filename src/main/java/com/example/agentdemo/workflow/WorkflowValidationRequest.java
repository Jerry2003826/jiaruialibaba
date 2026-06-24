package com.example.agentdemo.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record WorkflowValidationRequest(
        @NotNull @Valid WorkflowDefinition workflowDefinition) {
}
