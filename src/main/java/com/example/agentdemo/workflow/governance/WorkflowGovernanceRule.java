package com.example.agentdemo.workflow.governance;

import java.util.List;

public record WorkflowGovernanceRule(
        String id,
        String severity,
        String title,
        String description,
        List<String> antiPatterns,
        List<String> examples,
        String repairHint,
        String detector) {

    public WorkflowGovernanceRule {
        antiPatterns = antiPatterns == null ? List.of() : List.copyOf(antiPatterns);
        examples = examples == null ? List.of() : List.copyOf(examples);
    }
}
