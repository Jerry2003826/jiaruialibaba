package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowGraphPreviewService {

    private final WorkflowCompiler workflowCompiler;

    public WorkflowGraphPreviewService(WorkflowCompiler workflowCompiler) {
        this.workflowCompiler = workflowCompiler;
    }

    public WorkflowGraphPreviewResponse preview(WorkflowGraphPreviewRequest request) {
        WorkflowDefinition definition = request.workflowDefinition();
        try {
            WorkflowExecutionPlan executionPlan = workflowCompiler.compile(definition);
            List<WorkflowGraphNodeView> nodes = graphNodes(definition);
            List<WorkflowGraphEdgeView> edges = graphEdges(definition);
            return WorkflowGraphPreviewResponse.valid(
                    WorkflowValidationSummaryFactory.from(definition, executionPlan),
                    nodes,
                    edges,
                    mermaid(nodes, edges));
        }
        catch (BusinessException ex) {
            return WorkflowGraphPreviewResponse.invalid(ex.getCode(), ex.getMessage());
        }
    }

    private List<WorkflowGraphNodeView> graphNodes(WorkflowDefinition definition) {
        return definition.nodes()
                .stream()
                .map(node -> new WorkflowGraphNodeView(node.id(), node.type(), node.id() + " (" + node.type() + ")"))
                .toList();
    }

    private List<WorkflowGraphEdgeView> graphEdges(WorkflowDefinition definition) {
        return definition.edges()
                .stream()
                .map(edge -> {
                    WorkflowExecutionEdge executionEdge = new WorkflowExecutionEdge(edge);
                    String label = StringUtils.hasText(executionEdge.condition()) ? executionEdge.condition() : null;
                    return new WorkflowGraphEdgeView(executionEdge.from(), executionEdge.to(),
                            executionEdge.condition(), label);
                })
                .toList();
    }

    private String mermaid(List<WorkflowGraphNodeView> nodes, List<WorkflowGraphEdgeView> edges) {
        Map<String, String> aliasesByNodeId = new LinkedHashMap<>();
        StringBuilder builder = new StringBuilder("flowchart TD");
        for (int i = 0; i < nodes.size(); i++) {
            WorkflowGraphNodeView node = nodes.get(i);
            String alias = "n" + i;
            aliasesByNodeId.put(node.id(), alias);
            builder.append('\n')
                    .append("  ")
                    .append(alias)
                    .append("[\"")
                    .append(escapeMermaidLabel(node.label()))
                    .append("\"]");
        }
        for (WorkflowGraphEdgeView edge : edges) {
            String fromAlias = aliasesByNodeId.get(edge.from());
            String toAlias = aliasesByNodeId.get(edge.to());
            builder.append('\n')
                    .append("  ")
                    .append(fromAlias);
            if (StringUtils.hasText(edge.label())) {
                builder.append(" -- \"")
                        .append(escapeMermaidLabel(edge.label()))
                        .append("\" --> ");
            }
            else {
                builder.append(" --> ");
            }
            builder.append(toAlias);
        }
        return builder.toString();
    }

    private String escapeMermaidLabel(String label) {
        return label.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("[", "&#91;")
                .replace("]", "&#93;")
                .replace("\r", " ")
                .replace("\n", " ");
    }

}
