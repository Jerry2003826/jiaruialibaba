package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowValidationSummary(
        int nodeCount,
        int edgeCount,
        boolean linear,
        String startNodeId,
        String endNodeId,
        List<String> nodeTypes) {

    public WorkflowValidationSummary {
        nodeTypes = List.copyOf(nodeTypes);
    }

}
