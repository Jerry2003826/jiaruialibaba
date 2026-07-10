package com.example.agentdemo.workflow.governance;

public record WorkflowEvaluationCase(
        String id,
        String prompt,
        String expectedBehavior) {
}
