package com.example.agentdemo.workflow;

import org.springframework.util.StringUtils;

import java.util.Locale;

public record WorkflowExecutionEdge(String from, String to, String condition) {

    public WorkflowExecutionEdge(WorkflowEdge edge) {
        this(edge.from(), edge.to(), normalize(edge.condition()));
    }

    public boolean conditional() {
        return StringUtils.hasText(condition);
    }

    public boolean matches(Boolean conditionResult) {
        if (!conditional()) {
            return true;
        }
        if (conditionResult == null) {
            return false;
        }
        return Boolean.parseBoolean(condition) == conditionResult;
    }

    private static String normalize(String condition) {
        return StringUtils.hasText(condition) ? condition.trim().toLowerCase(Locale.ROOT) : null;
    }

}
