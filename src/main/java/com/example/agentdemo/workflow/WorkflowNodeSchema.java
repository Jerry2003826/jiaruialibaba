package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowNodeSchema(
        String type,
        String displayName,
        String description,
        String group,
        List<WorkflowNodeConfigField> configFields,
        List<String> templateVariables,
        String outputDescription) {

    public WorkflowNodeSchema {
        configFields = configFields == null ? List.of() : List.copyOf(configFields);
        templateVariables = templateVariables == null ? List.of() : List.copyOf(templateVariables);
    }

    /** Compatibility constructor for callers that do not set a palette group. */
    public WorkflowNodeSchema(String type, String displayName, String description,
            List<WorkflowNodeConfigField> configFields, List<String> templateVariables, String outputDescription) {
        this(type, displayName, description, null, configFields, templateVariables, outputDescription);
    }

    /** Returns a copy of this schema with the given palette group. */
    public WorkflowNodeSchema withGroup(String group) {
        return new WorkflowNodeSchema(type, displayName, description, group, configFields, templateVariables,
                outputDescription);
    }

}
