package com.example.agentdemo.tool;

import java.time.Instant;

public record ToolExecutionLog(String toolName, Object input, Object output, boolean succeeded,
        String errorMessage, Instant startedAt, Instant endedAt) {

    public record EmptyInput() {
    }

    public record CalculateInput(String expression) {
    }

}
