package com.example.agentdemo.workflow;

import java.util.Map;

public record WorkflowNodeConfigField(
        String name,
        String type,
        boolean required,
        Object defaultValue,
        String description,
        Map<String, Object> constraints) {

    public WorkflowNodeConfigField {
        constraints = constraints == null ? Map.of() : Map.copyOf(constraints);
    }

}
