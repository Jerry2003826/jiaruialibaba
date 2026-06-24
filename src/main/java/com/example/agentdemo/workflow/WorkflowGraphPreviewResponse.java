package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowGraphPreviewResponse(
        boolean valid,
        List<WorkflowValidationError> errors,
        WorkflowValidationSummary summary,
        List<WorkflowGraphNodeView> nodes,
        List<WorkflowGraphEdgeView> edges,
        String mermaid) {

    public WorkflowGraphPreviewResponse {
        errors = List.copyOf(errors);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        mermaid = mermaid == null ? "" : mermaid;
    }

    static WorkflowGraphPreviewResponse valid(WorkflowValidationSummary summary, List<WorkflowGraphNodeView> nodes,
            List<WorkflowGraphEdgeView> edges, String mermaid) {
        return new WorkflowGraphPreviewResponse(true, List.of(), summary, nodes, edges, mermaid);
    }

    static WorkflowGraphPreviewResponse invalid(String code, String message) {
        return new WorkflowGraphPreviewResponse(false, List.of(new WorkflowValidationError(code, message)), null,
                List.of(), List.of(), "");
    }

}
