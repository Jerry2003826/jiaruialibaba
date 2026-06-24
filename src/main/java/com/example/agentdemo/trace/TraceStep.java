package com.example.agentdemo.trace;

public record TraceStep(
        String stepId,
        String runId,
        String nodeName) {
}
