package com.example.agentdemo.app.dto;

/**
 * Runtime response from running a WORKFLOW app.
 *
 * @param output                    workflow output
 * @param runId                     trace run id
 * @param appId                     originating app id
 * @param workflowDefinitionId      executed workflow id
 * @param workflowDefinitionVersion executed workflow version (pinned by the published snapshot)
 */
public record AppRunResultResponse(Object output, String runId, String appId, String workflowDefinitionId,
        Integer workflowDefinitionVersion) {
}
