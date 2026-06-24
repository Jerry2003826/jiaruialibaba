package com.example.agentdemo.workflow;

public record WorkflowDefinitionResolution(
        String definitionId,
        Integer version,
        WorkflowDefinition workflowDefinition) {
}
