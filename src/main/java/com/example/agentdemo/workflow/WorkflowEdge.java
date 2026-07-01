package com.example.agentdemo.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkflowEdge(
        @NotBlank @Size(max = 128) String from,
        @NotBlank @Size(max = 128) String to,
        @Size(max = 32) String condition,
        @Size(max = 128) String label,
        @Size(max = 64) String route) {

    public WorkflowEdge(String from, String to) {
        this(from, to, null, null, null);
    }

    public WorkflowEdge(String from, String to, String condition) {
        this(from, to, condition, null, null);
    }

    public WorkflowEdge {
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
