package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkflowGraphPreviewService {

    private final WorkflowCompiler workflowCompiler;
    private final WorkflowGraphRenderer workflowGraphRenderer;

    public WorkflowGraphPreviewService(WorkflowCompiler workflowCompiler, WorkflowGraphRenderer workflowGraphRenderer) {
        this.workflowCompiler = workflowCompiler;
        this.workflowGraphRenderer = workflowGraphRenderer;
    }

    public WorkflowGraphPreviewResponse preview(WorkflowGraphPreviewRequest request) {
        WorkflowDefinition definition = request.workflowDefinition();
        try {
            WorkflowExecutionPlan executionPlan = workflowCompiler.compile(definition);
            List<WorkflowGraphNodeView> nodes = workflowGraphRenderer.previewNodes(definition);
            List<WorkflowGraphEdgeView> edges = workflowGraphRenderer.previewEdges(definition);
            return WorkflowGraphPreviewResponse.valid(
                    WorkflowValidationSummaryFactory.from(definition, executionPlan),
                    nodes,
                    edges,
                    workflowGraphRenderer.previewMermaid(nodes, edges));
        }
        catch (BusinessException ex) {
            return WorkflowGraphPreviewResponse.invalid(ex.getCode(), ex.getMessage());
        }
    }

}
