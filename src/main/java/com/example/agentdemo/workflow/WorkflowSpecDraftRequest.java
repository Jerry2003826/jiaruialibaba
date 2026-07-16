package com.example.agentdemo.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkflowSpecDraftRequest(
        @NotBlank @Size(max = 4000) String prompt) {
}
