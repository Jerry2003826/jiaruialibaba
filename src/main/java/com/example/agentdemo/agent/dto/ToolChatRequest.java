package com.example.agentdemo.agent.dto;

import com.example.agentdemo.workflow.WorkflowDefinition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ToolChatRequest(
        @Size(max = 128) String conversationId,
        @NotBlank @Size(max = 4000) String message,
        @Valid WorkflowDefinition workflowDefinition,
        @Size(max = 64) String workflowDefinitionId,
        @Positive Integer workflowDefinitionVersion) {

    public ToolChatRequest(String conversationId, String message) {
        this(conversationId, message, null, null, null);
    }

    public ToolChatRequest(String conversationId, String message, String workflowDefinitionId,
            Integer workflowDefinitionVersion) {
        this(conversationId, message, null, workflowDefinitionId, workflowDefinitionVersion);
    }
}
