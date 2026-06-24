package com.example.agentdemo.workflow;

public record WorkflowRunGraphEdgeView(
        String from,
        String to,
        String condition,
        String label,
        boolean traversed) {
}
