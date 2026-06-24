package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStatus;

import java.time.Instant;

public record WorkflowRunRecordResponse(
        String runId,
        String definitionId,
        Integer definitionVersion,
        Instant startedAt,
        RunStatus status,
        String output,
        String errorMessage,
        Instant endedAt) {
}
