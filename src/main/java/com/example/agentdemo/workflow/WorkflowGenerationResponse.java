package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowGenerationResponse(
        String name,
        String description,
        WorkflowDefinition workflowDefinition,
        List<String> notes) {
}
