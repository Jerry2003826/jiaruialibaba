package com.example.agentdemo.app.dto;

import com.example.agentdemo.app.AppConfig;
import com.example.agentdemo.app.AppType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to create an app (starts as DRAFT).
 *
 * @param name                  display name (required)
 * @param description           optional description
 * @param type                  app type (required)
 * @param config                type-specific config (CHAT/AGENT); may be null
 * @param workflowDefinitionId  bound workflow id (required for WORKFLOW apps)
 * @param workflowDefinitionVersion optional pinned workflow version; null resolves at publish time
 */
public record CreateAppRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1024) String description,
        @NotNull AppType type,
        AppConfig config,
        @Size(max = 64) String workflowDefinitionId,
        Integer workflowDefinitionVersion) {
}
