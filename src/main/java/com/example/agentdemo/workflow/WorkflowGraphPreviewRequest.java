package com.example.agentdemo.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record WorkflowGraphPreviewRequest(
        @NotNull @Valid WorkflowDefinition workflowDefinition) {
}
