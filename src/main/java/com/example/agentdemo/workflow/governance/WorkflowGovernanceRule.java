package com.example.agentdemo.workflow.governance;

public record WorkflowGovernanceRule(
        String id,
        String severity,
        String title,
        String description,
        String repairHint,
        String detector) {
}
