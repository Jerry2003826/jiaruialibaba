package com.example.agentdemo.tool;

import java.time.Duration;
import java.time.Instant;

public record ToolExecutionLog(String toolName, Object input, Object output, boolean succeeded,
        String errorMessage, Instant startedAt, Instant endedAt, String provider, boolean remote, String serverName,
        long durationMs, String errorCategory, String errorType) {

    public static final String ERROR_TOOL_NOT_FOUND = "TOOL_NOT_FOUND";
    public static final String ERROR_TOOL_NOT_ALLOWED = "TOOL_NOT_ALLOWED";
    public static final String ERROR_VALIDATION = "VALIDATION_ERROR";
    public static final String ERROR_SERIALIZATION = "SERIALIZATION_ERROR";
    public static final String ERROR_REMOTE_TOOL = "REMOTE_TOOL_ERROR";
    public static final String ERROR_EXECUTION = "EXECUTION_ERROR";
    public static final String ERROR_TYPE_NORMAL = "NORMAL";
    public static final String ERROR_TYPE_RAW_REMOTE = "RAW_REMOTE";

    public ToolExecutionLog(String toolName, Object input, Object output, boolean succeeded,
            String errorMessage, Instant startedAt, Instant endedAt) {
        this(toolName, input, output, succeeded, errorMessage, startedAt, endedAt, null, false, null,
                durationMillis(startedAt, endedAt), succeeded ? null : ERROR_EXECUTION,
                succeeded ? null : ERROR_TYPE_NORMAL);
    }

    public ToolExecutionLog withDescriptor(ToolDescriptor descriptor) {
        if (provider != null || descriptor == null) {
            return this;
        }
        return new ToolExecutionLog(toolName, input, output, succeeded, errorMessage, startedAt, endedAt,
                descriptor.provider(), descriptor.remote(), descriptor.serverName(), durationMs, errorCategory,
                errorType);
    }

    public static ToolExecutionLog failure(String toolName, Object input, String errorMessage, Instant startedAt,
            Instant endedAt, ToolDescriptor descriptor, String errorCategory) {
        return failure(toolName, input, errorMessage, startedAt, endedAt, descriptor, errorCategory, ERROR_TYPE_NORMAL);
    }

    public static ToolExecutionLog failure(String toolName, Object input, String errorMessage, Instant startedAt,
            Instant endedAt, ToolDescriptor descriptor, String errorCategory, String errorType) {
        return new ToolExecutionLog(toolName, input, null, false, errorMessage, startedAt, endedAt,
                descriptor == null ? null : descriptor.provider(), descriptor != null && descriptor.remote(),
                descriptor == null ? null : descriptor.serverName(), durationMillis(startedAt, endedAt),
                errorCategory, errorType);
    }

    public static ToolExecutionLog success(String toolName, Object input, Object output, Instant startedAt,
            Instant endedAt, ToolDescriptor descriptor) {
        return new ToolExecutionLog(toolName, input, output, true, null, startedAt, endedAt,
                descriptor == null ? null : descriptor.provider(), descriptor != null && descriptor.remote(),
                descriptor == null ? null : descriptor.serverName(), durationMillis(startedAt, endedAt), null, null);
    }

    private static long durationMillis(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(startedAt, endedAt).toMillis());
    }

    public record EmptyInput() {
    }

    public record CalculateInput(String expression) {
    }

    public record OrderQueryInput(String userQuery) {
    }

}
