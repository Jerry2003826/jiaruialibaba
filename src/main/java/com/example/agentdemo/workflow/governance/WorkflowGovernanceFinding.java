package com.example.agentdemo.workflow.governance;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkflowGovernanceFinding(
        String ruleId,
        Severity severity,
        Phase phase,
        String message,
        List<String> nodeIds,
        String repairHint,
        Map<String, Object> evidence) {

    public enum Severity {
        BLOCK,
        WARN
    }

    public enum Phase {
        STATIC
    }

    public WorkflowGovernanceFinding {
        ruleId = normalize(ruleId);
        severity = severity == null ? Severity.WARN : severity;
        phase = phase == null ? Phase.STATIC : phase;
        message = normalize(message);
        nodeIds = nodeIds == null ? List.of() : nodeIds.stream().sorted().distinct().toList();
        repairHint = normalize(repairHint);
        evidence = evidence == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(evidence));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
