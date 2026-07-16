package com.example.agentdemo.workflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record WorkflowDefinitionResponse(
        String definitionId,
        String name,
        String description,
        WorkflowDefinition workflowDefinition,
        JsonNode layout,
        WorkflowVariableSchema variables,
        Integer version,
        WorkflowDefinitionStatus status,
        Instant createdAt,
        Instant updatedAt,
        JsonNode lockedSpec) {

    public WorkflowDefinitionResponse(String definitionId, String name, String description,
            WorkflowDefinition workflowDefinition, JsonNode layout, WorkflowVariableSchema variables,
            Integer version, WorkflowDefinitionStatus status, Instant createdAt, Instant updatedAt) {
        this(definitionId, name, description, workflowDefinition, layout, variables, version, status,
                createdAt, updatedAt, null);
    }

    /** Compatibility constructor for callers that do not carry layout/variables. */
    public WorkflowDefinitionResponse(String definitionId, String name, String description,
            WorkflowDefinition workflowDefinition, Integer version, WorkflowDefinitionStatus status,
            Instant createdAt, Instant updatedAt) {
        this(definitionId, name, description, workflowDefinition, null, null, version, status, createdAt, updatedAt,
                null);
    }
}
