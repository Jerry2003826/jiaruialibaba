package com.example.agentdemo.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record WorkflowNode(
        @NotBlank @Size(max = 128) String id,
        @NotBlank @Size(max = 32) String type,
        Map<String, Object> config) {

    public WorkflowNode {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

}
