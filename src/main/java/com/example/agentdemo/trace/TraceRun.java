package com.example.agentdemo.trace;

import java.time.Instant;

public record TraceRun(
        String runId,
        Instant startedAt) {
}
