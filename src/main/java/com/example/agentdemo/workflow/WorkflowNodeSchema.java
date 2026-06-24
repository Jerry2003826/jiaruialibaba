package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowNodeSchema(
        String type,
        String displayName,
        String description,
        List<WorkflowNodeConfigField> configFields,
        List<String> templateVariables,
        String outputDescription) {

    public WorkflowNodeSchema {
        configFields = configFields == null ? List.of() : List.copyOf(configFields);
        templateVariables = templateVariables == null ? List.of() : List.copyOf(templateVariables);
    }

}
