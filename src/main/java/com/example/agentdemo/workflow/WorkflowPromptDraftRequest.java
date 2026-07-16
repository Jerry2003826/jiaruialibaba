package com.example.agentdemo.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkflowPromptDraftRequest(
        @NotBlank @Size(max = 2000) String requirement,
        @Size(max = 200) String nodeLabel,
        @Size(max = 200) String inputLabel) {
}
