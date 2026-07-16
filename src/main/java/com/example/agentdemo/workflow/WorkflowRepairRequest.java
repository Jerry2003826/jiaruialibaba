package com.example.agentdemo.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.databind.JsonNode;

public record WorkflowRepairRequest(
        @Size(max = 4000) String prompt,
        @NotBlank @Size(max = 4000) String error,
        @Size(max = 128) String name,
        @Size(max = 1000) String description,
        @NotNull @Valid WorkflowDefinition workflowDefinition,
        JsonNode lockedSpec) {

    public WorkflowRepairRequest(String prompt, String error, String name, String description,
            WorkflowDefinition workflowDefinition) {
        this(prompt, error, name, description, workflowDefinition, null);
    }
}
