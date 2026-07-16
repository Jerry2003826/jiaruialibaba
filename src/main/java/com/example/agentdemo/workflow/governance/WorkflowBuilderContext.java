package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.workflow.WorkflowNodeSchema;

import java.util.List;

public record WorkflowBuilderContext(
        String domain,
        String lockedSpec,
        String previousFailure,
        List<WorkflowRulePack> activeRulePacks,
        List<WorkflowNodeSchema> nodeSchemas,
        List<ToolDescriptor> executableTools,
        List<Citation> citations,
        String promptSection) {

    public WorkflowBuilderContext {
        domain = normalize(domain);
        lockedSpec = normalize(lockedSpec);
        previousFailure = normalize(previousFailure);
        activeRulePacks = activeRulePacks == null ? List.of() : List.copyOf(activeRulePacks);
        nodeSchemas = nodeSchemas == null ? List.of() : List.copyOf(nodeSchemas);
        executableTools = executableTools == null ? List.of() : List.copyOf(executableTools);
        citations = citations == null ? List.of() : List.copyOf(citations);
        promptSection = normalize(promptSection);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
