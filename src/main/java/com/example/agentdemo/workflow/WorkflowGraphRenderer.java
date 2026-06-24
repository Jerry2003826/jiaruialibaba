package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WorkflowGraphRenderer {

    public List<WorkflowGraphNodeView> previewNodes(WorkflowDefinition definition) {
        return definition.nodes()
                .stream()
                .map(node -> new WorkflowGraphNodeView(node.id(), node.type(), label(node.id(), node.type())))
                .toList();
    }

    public List<WorkflowGraphEdgeView> previewEdges(WorkflowDefinition definition) {
        return definition.edges()
                .stream()
                .map(edge -> {
                    WorkflowExecutionEdge executionEdge = new WorkflowExecutionEdge(edge);
                    String label = edgeLabel(executionEdge.condition());
                    return new WorkflowGraphEdgeView(executionEdge.from(), executionEdge.to(),
                            executionEdge.condition(), label);
                })
                .toList();
    }

    public String previewMermaid(List<WorkflowGraphNodeView> nodes, List<WorkflowGraphEdgeView> edges) {
        return buildMermaid(
                nodes.stream().map(WorkflowGraphNodeView::id).toList(),
                nodes.stream().map(WorkflowGraphNodeView::label).toList(),
                builder -> edges.forEach(edge -> appendEdge(builder, edge.from(), edge.to(), edge.label(), true,
                        false)));
    }

    public List<WorkflowRunGraphNodeView> runNodes(WorkflowDefinition definition,
            Map<String, RunStepResponse> stepsByNodeName) {
        return definition.nodes()
                .stream()
                .map(node -> runNode(node, stepsByNodeName.get(node.id())))
                .toList();
    }

    public List<WorkflowRunGraphEdgeView> runEdges(WorkflowDefinition definition,
            Map<String, RunStepResponse> stepsByNodeName) {
        return definition.edges()
                .stream()
                .map(edge -> {
                    WorkflowExecutionEdge executionEdge = new WorkflowExecutionEdge(edge);
                    String label = edgeLabel(executionEdge.condition());
                    boolean traversed = stepsByNodeName.containsKey(executionEdge.from())
                            && stepsByNodeName.containsKey(executionEdge.to());
                    return new WorkflowRunGraphEdgeView(executionEdge.from(), executionEdge.to(),
                            executionEdge.condition(), label, traversed);
                })
                .toList();
    }

    public String runMermaid(List<WorkflowRunGraphNodeView> nodes, List<WorkflowRunGraphEdgeView> edges) {
        String mermaid = buildMermaid(
                nodes.stream().map(WorkflowRunGraphNodeView::id).toList(),
                nodes.stream().map(WorkflowRunGraphNodeView::label).toList(),
                builder -> edges.forEach(edge -> appendEdge(builder, edge.from(), edge.to(), edge.label(),
                        edge.traversed(), true)));
        return mermaid + runNodeClasses(nodes);
    }

    private WorkflowRunGraphNodeView runNode(WorkflowNode node, RunStepResponse step) {
        String statusLabel = step == null ? "NOT_EXECUTED" : step.status().name();
        return new WorkflowRunGraphNodeView(node.id(), node.type(),
                label(node.id(), node.type()) + " " + statusLabel,
                step != null,
                step == null ? null : step.status(),
                step == null ? null : step.stepId(),
                step == null ? null : step.errorMessage());
    }

    private String buildMermaid(List<String> nodeIds, List<String> nodeLabels, MermaidEdgeAppender edgeAppender) {
        Map<String, String> aliasesByNodeId = new LinkedHashMap<>();
        StringBuilder builder = new StringBuilder("flowchart TD");
        for (int i = 0; i < nodeIds.size(); i++) {
            String alias = "n" + i;
            aliasesByNodeId.put(nodeIds.get(i), alias);
            builder.append('\n')
                    .append("  ")
                    .append(alias)
                    .append("[\"")
                    .append(escapeMermaidLabel(nodeLabels.get(i)))
                    .append("\"]");
        }
        edgeAppender.append(new AliasedMermaidBuilder(builder, aliasesByNodeId));
        return builder.toString();
    }

    private void appendEdge(AliasedMermaidBuilder builder, String from, String to, String label, boolean solid,
            boolean renderDashedWhenNotSolid) {
        StringBuilder mermaid = builder.builder();
        mermaid.append('\n')
                .append("  ")
                .append(builder.alias(from));
        if (solid) {
            appendSolidEdge(mermaid, label);
        }
        else if (renderDashedWhenNotSolid) {
            appendDashedEdge(mermaid, label);
        }
        else {
            mermaid.append(" --> ");
        }
        mermaid.append(builder.alias(to));
    }

    private void appendSolidEdge(StringBuilder builder, String label) {
        if (StringUtils.hasText(label)) {
            builder.append(" -- \"")
                    .append(escapeMermaidLabel(label))
                    .append("\" --> ");
            return;
        }
        builder.append(" --> ");
    }

    private void appendDashedEdge(StringBuilder builder, String label) {
        if (StringUtils.hasText(label)) {
            builder.append(" -. \"")
                    .append(escapeMermaidLabel(label))
                    .append("\" .-> ");
            return;
        }
        builder.append(" -.-> ");
    }

    private String runNodeClasses(List<WorkflowRunGraphNodeView> nodes) {
        StringBuilder builder = new StringBuilder();
        builder.append('\n')
                .append("  classDef succeeded fill:#e8f5e9,stroke:#2e7d32,color:#111")
                .append('\n')
                .append("  classDef failed fill:#ffebee,stroke:#c62828,color:#111")
                .append('\n')
                .append("  classDef running fill:#fff8e1,stroke:#f9a825,color:#111")
                .append('\n')
                .append("  classDef notExecuted fill:#f5f5f5,stroke:#9e9e9e,color:#555");
        for (int i = 0; i < nodes.size(); i++) {
            builder.append('\n')
                    .append("  class n")
                    .append(i)
                    .append(' ')
                    .append(nodeClass(nodes.get(i).status()));
        }
        return builder.toString();
    }

    private String nodeClass(StepStatus status) {
        if (status == null) {
            return "notExecuted";
        }
        return switch (status) {
            case SUCCEEDED -> "succeeded";
            case FAILED -> "failed";
            case RUNNING -> "running";
        };
    }

    private String label(String nodeId, String nodeType) {
        return nodeId + " (" + nodeType + ")";
    }

    private String edgeLabel(String condition) {
        return StringUtils.hasText(condition) ? condition : null;
    }

    private String escapeMermaidLabel(String label) {
        return label.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("[", "&#91;")
                .replace("]", "&#93;")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    @FunctionalInterface
    private interface MermaidEdgeAppender {

        void append(AliasedMermaidBuilder builder);

    }

    private record AliasedMermaidBuilder(StringBuilder builder, Map<String, String> aliasesByNodeId) {

        String alias(String nodeId) {
            return aliasesByNodeId.get(nodeId);
        }

    }

}
