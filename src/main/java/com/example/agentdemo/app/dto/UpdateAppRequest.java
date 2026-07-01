package com.example.agentdemo.app.dto;

import com.example.agentdemo.app.AppConfig;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request to update an app draft. The type is immutable after creation; updating produces a new
 * draft version.
 *
 * @param name                      display name (required)
 * @param description               optional description
 * @param config                    type-specific config (CHAT/AGENT); may be null
 * @param workflowDefinitionId      bound workflow id (WORKFLOW apps)
 * @param workflowDefinitionVersion optional pinned workflow version
 */
public record UpdateAppRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 1024) String description,
        @Valid AppConfig config,
        @Size(max = 64) String workflowDefinitionId,
        Integer workflowDefinitionVersion) {
}
