package com.example.agentdemo.workflow;

public record WorkflowGraphEdgeView(
        String from,
        String to,
        String condition,
        String label) {
}
