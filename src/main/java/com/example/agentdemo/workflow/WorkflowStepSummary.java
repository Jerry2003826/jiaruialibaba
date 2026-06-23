package com.example.agentdemo.workflow;

public record WorkflowStepSummary(String nodeId, String nodeType, String status, Object output) {
}
