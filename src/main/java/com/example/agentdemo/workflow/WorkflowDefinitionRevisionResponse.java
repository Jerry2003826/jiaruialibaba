package com.example.agentdemo.workflow;

import java.time.Instant;

public record WorkflowDefinitionRevisionResponse(
        String definitionId,
        Integer version,
        WorkflowDefinitionStatus status,
        String name,
        String description,
        WorkflowDefinition workflowDefinition,
        Instant createdAt,
        Instant updatedAt,
        com.fasterxml.jackson.databind.JsonNode lockedSpec) {

    public WorkflowDefinitionRevisionResponse(String definitionId, Integer version, WorkflowDefinitionStatus status,
            String name, String description, WorkflowDefinition workflowDefinition, Instant createdAt,
            Instant updatedAt) {
        this(definitionId, version, status, name, description, workflowDefinition, createdAt, updatedAt, null);
    }
}
