package com.example.agentdemo.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WorkflowEdge(
        @NotBlank @Size(max = 128) String from,
        @NotBlank @Size(max = 128) String to) {
}
