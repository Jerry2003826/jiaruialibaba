package com.example.agentdemo.workflow.governance;

import java.util.List;

public record WorkflowRulePack(
        String id,
        String version,
        List<String> domains,
        List<WorkflowGovernanceRule> rules,
        List<String> knowledgeEntries,
        List<WorkflowEvaluationCase> testCases) {

    public WorkflowRulePack {
        domains = domains == null ? List.of() : List.copyOf(domains);
        rules = rules == null ? List.of() : List.copyOf(rules);
        knowledgeEntries = knowledgeEntries == null ? List.of() : List.copyOf(knowledgeEntries);
        testCases = testCases == null ? List.of() : List.copyOf(testCases);
    }
}
