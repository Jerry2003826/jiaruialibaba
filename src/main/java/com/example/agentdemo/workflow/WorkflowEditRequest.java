package com.example.agentdemo.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.databind.JsonNode;

public record WorkflowEditRequest(
        @NotBlank @Size(max = 4000) String prompt,
        @Size(max = 128) String name,
        @Size(max = 1000) String description,
        @NotNull @Valid WorkflowDefinition workflowDefinition,
        JsonNode lockedSpec) {

    public WorkflowEditRequest(String prompt, String name, String description,
            WorkflowDefinition workflowDefinition) {
        this(prompt, name, description, workflowDefinition, null);
    }
}
