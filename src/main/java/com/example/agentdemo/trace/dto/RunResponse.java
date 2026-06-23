package com.example.agentdemo.trace.dto;

import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;

import java.time.Instant;

public record RunResponse(String runId, RunType type, RunStatus status, String input, String output,
        String errorMessage, Instant startedAt, Instant endedAt) {
}
