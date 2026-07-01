package com.example.agentdemo.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowNode(
        @NotBlank @Size(max = 128) String id,
        @NotBlank @Size(max = 32) String type,
        Map<String, Object> config,
        @Size(max = 128) String label,
        @Size(max = 64) String route) {

    public WorkflowNode(String id, String type, Map<String, Object> config) {
        this(id, type, config, null, null);
    }

    public WorkflowNode {
        config = config == null ? Map.of() : Map.copyOf(config);
        label = normalize(label);
        route = normalize(route);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
