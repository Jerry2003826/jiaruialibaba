package com.example.agentdemo.workflow.governance;

import java.util.Comparator;
import java.util.List;

public record WorkflowGovernanceReport(List<WorkflowGovernanceFinding> findings) {

    public WorkflowGovernanceReport {
        findings = findings == null ? List.of() : findings.stream()
                .sorted(Comparator.comparing(WorkflowGovernanceFinding::ruleId)
                        .thenComparing(finding -> String.join("\u0000", finding.nodeIds()))
                        .thenComparing(WorkflowGovernanceFinding::message))
                .toList();
    }

    public boolean hasBlocks() {
        return findings.stream()
                .anyMatch(finding -> finding.severity() == WorkflowGovernanceFinding.Severity.BLOCK);
    }

    public List<WorkflowGovernanceFinding> blockers() {
        return findings.stream()
                .filter(finding -> finding.severity() == WorkflowGovernanceFinding.Severity.BLOCK)
                .toList();
    }

    public List<WorkflowGovernanceFinding> warnings() {
        return findings.stream()
                .filter(finding -> finding.severity() == WorkflowGovernanceFinding.Severity.WARN)
                .toList();
    }
}
