package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStatus;

import java.util.List;

public record WorkflowRunGraphResponse(
        String runId,
        String definitionId,
        Integer definitionVersion,
        RunStatus status,
        WorkflowValidationSummary summary,
        List<WorkflowRunGraphNodeView> nodes,
        List<WorkflowRunGraphEdgeView> edges,
        String mermaid) {

    public WorkflowRunGraphResponse {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        mermaid = mermaid == null ? "" : mermaid;
    }

}
