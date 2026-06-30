package com.example.agentdemo.workflow;

import jakarta.validation.constraints.NotBlank;

public record WorkflowGenerationRequest(
        @NotBlank String prompt) {
}
