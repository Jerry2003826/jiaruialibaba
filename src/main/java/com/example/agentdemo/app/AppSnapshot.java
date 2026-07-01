package com.example.agentdemo.app;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Immutable snapshot of an app version, persisted as {@code app_revisions.snapshot_json}. A
 * published app runs from its published snapshot so historical runs remain reproducible even after
 * the draft is edited.
 *
 * @param name                       app name at snapshot time
 * @param description                app description at snapshot time
 * @param type                       app type
 * @param config                     type-specific config
 * @param workflowDefinitionId       bound workflow id (WORKFLOW apps)
 * @param workflowDefinitionVersion  pinned workflow version (WORKFLOW apps)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppSnapshot(String name, String description, AppType type, AppConfig config,
        String workflowDefinitionId, Integer workflowDefinitionVersion) {
}
