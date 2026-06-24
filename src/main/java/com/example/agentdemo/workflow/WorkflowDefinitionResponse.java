package com.example.agentdemo.workflow;

import java.time.Instant;

public record WorkflowDefinitionResponse(
        String definitionId,
        String name,
        String description,
        WorkflowDefinition workflowDefinition,
        Integer version,
        WorkflowDefinitionStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
