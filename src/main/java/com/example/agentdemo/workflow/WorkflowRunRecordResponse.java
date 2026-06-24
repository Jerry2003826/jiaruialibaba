package com.example.agentdemo.workflow;

import java.time.Instant;

public record WorkflowRunRecordResponse(
        String runId,
        String definitionId,
        Integer definitionVersion,
        Instant startedAt) {
}
