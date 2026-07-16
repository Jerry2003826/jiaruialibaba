package com.example.agentdemo.workflow;

import com.example.agentdemo.tool.ToolExecutionLog;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class WorkflowEvaluationFixtures {

    public static final String ERROR_EVALUATION_TOOL_FAILURE = "WORKFLOW_EVALUATION_TOOL_FAILURE";

    private WorkflowEvaluationFixtures() {
    }

    public static Map<String, Object> withToolFailure(Map<String, Object> input, String toolName) {
        return new FixtureInput(input == null ? Map.of() : input, toolName);
    }

    public static Optional<ToolExecutionLog> failedToolCall(Map<String, Object> input, String toolName,
            Object arguments) {
        if (!(input instanceof FixtureInput fixtureInput) || !fixtureInput.failTool().equals(toolName)) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return Optional.of(ToolExecutionLog.failure(
                toolName,
                arguments,
                "Injected workflow evaluation tool failure",
                now,
                now,
                null,
                ERROR_EVALUATION_TOOL_FAILURE));
    }

    private static final class FixtureInput extends AbstractMap<String, Object> {

        private final Map<String, Object> delegate;
        private final String failTool;

        private FixtureInput(Map<String, Object> delegate, String failTool) {
            this.delegate = Map.copyOf(delegate);
            this.failTool = failTool;
        }

        private String failTool() {
            return failTool;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return delegate.entrySet();
        }
    }
}
