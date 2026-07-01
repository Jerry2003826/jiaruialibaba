package com.example.agentdemo.app.dto;

import com.example.agentdemo.app.AppConfig;
import com.example.agentdemo.app.AppStatus;
import com.example.agentdemo.app.AppType;

import java.time.Instant;

/**
 * API view of an app.
 */
public record AppResponse(String appId, String name, String description, AppType type, AppStatus status,
        AppConfig config, String workflowDefinitionId, Integer workflowDefinitionVersion, int version,
        Integer publishedVersion, Instant createdAt, Instant updatedAt) {
}
