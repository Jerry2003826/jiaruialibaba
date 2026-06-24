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
        Instant updatedAt) {
}
