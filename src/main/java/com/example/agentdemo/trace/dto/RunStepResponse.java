package com.example.agentdemo.trace.dto;

import com.example.agentdemo.trace.StepStatus;

import java.time.Instant;

public record RunStepResponse(String stepId, String runId, String nodeName, String inputJson, String outputJson,
        String errorMessage, StepStatus status, Instant startedAt, Instant endedAt) {
}
