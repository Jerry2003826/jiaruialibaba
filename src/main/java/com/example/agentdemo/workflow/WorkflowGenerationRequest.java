package com.example.agentdemo.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.databind.JsonNode;

public record WorkflowGenerationRequest(
        @NotBlank @Size(max = 4000) String prompt,
        JsonNode lockedSpec) {

    public WorkflowGenerationRequest(String prompt) {
        this(prompt, null);
    }
}
