package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WorkflowGraphRenderer {

    private static final String DYNAMIC_STEP_SEPARATOR = ":dynamic:";

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
                    String edgeLabel = edgeLabel(executionEdge.condition());
                    return new WorkflowGraphEdgeView(executionEdge.from(), executionEdge.to(),
                            executionEdge.condition(), edgeLabel);
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
            WorkflowExecutionPlan executionPlan, List<RunStepResponse> steps) {
        return runNodes(definition, executionPlan, steps, Map.of());
    }

    public List<WorkflowRunGraphNodeView> runNodes(WorkflowDefinition definition,
            WorkflowExecutionPlan executionPlan, List<RunStepResponse> steps,
            Map<String, String> stepTypesByNodeId) {
        if (!hasAdvancedStructure(definition, executionPlan)) {
            return runNodes(definition, latestStepsByNodeName(steps));
        }
        RunGraphContext context = new RunGraphContext(definition, executionPlan, steps, stepTypesByNodeId);
        return definition.nodes().stream().map(node -> buildRunNode(node, context)).toList();
    }

    public List<WorkflowRunGraphNodeView> runNodes(WorkflowDefinition definition,
            Map<String, RunStepResponse> stepsByNodeName) {
        return definition.nodes()
                .stream()
                .map(node -> runNode(node, stepsByNodeName.get(node.id())))
                .toList();
    }

    public List<WorkflowRunGraphEdgeView> runEdges(WorkflowDefinition definition,
            WorkflowExecutionPlan executionPlan, List<RunStepResponse> steps,
            List<WorkflowRunGraphNodeView> nodes) {
        if (!hasAdvancedStructure(definition, executionPlan)) {
            return runEdges(definition, latestStepsByNodeName(steps));
        }
        Map<String, WorkflowRunGraphNodeView> nodesById = nodes.stream()
                .collect(Collectors.toMap(WorkflowRunGraphNodeView::id, node -> node, (left, right) -> left,
                        LinkedHashMap::new));
        return definition.edges()
                .stream()
                .map(edge -> {
                    WorkflowExecutionEdge executionEdge = new WorkflowExecutionEdge(edge);
                    String edgeLabel = edgeLabel(executionEdge.condition());
                    boolean traversed = isEdgeTraversed(executionEdge, nodesById);
                    return new WorkflowRunGraphEdgeView(executionEdge.from(), executionEdge.to(),
                            executionEdge.condition(), edgeLabel, traversed);
                })
                .toList();
    }

    public List<WorkflowRunGraphEdgeView> runEdges(WorkflowDefinition definition,
            Map<String, RunStepResponse> stepsByNodeName) {
        return definition.edges()
                .stream()
                .map(edge -> {
                    WorkflowExecutionEdge executionEdge = new WorkflowExecutionEdge(edge);
                    String edgeLabel = edgeLabel(executionEdge.condition());
                    boolean traversed = stepsByNodeName.containsKey(executionEdge.from())
                            && stepsByNodeName.containsKey(executionEdge.to());
                    return new WorkflowRunGraphEdgeView(executionEdge.from(), executionEdge.to(),
                            executionEdge.condition(), edgeLabel, traversed);
                })
                .toList();
    }

    public String runMermaid(WorkflowDefinition definition, WorkflowExecutionPlan executionPlan,
            List<WorkflowRunGraphNodeView> nodes, List<WorkflowRunGraphEdgeView> edges) {
        if (!hasAdvancedStructure(definition, executionPlan)) {
            return runMermaid(nodes, edges);
        }
        return buildAdvancedRunMermaid(definition, executionPlan, nodes, edges);
    }

    public String runMermaid(List<WorkflowRunGraphNodeView> nodes, List<WorkflowRunGraphEdgeView> edges) {
        String mermaid = buildMermaid(
                nodes.stream().map(WorkflowRunGraphNodeView::id).toList(),
                nodes.stream().map(WorkflowRunGraphNodeView::label).toList(),
                builder -> edges.forEach(edge -> appendEdge(builder, edge.from(), edge.to(), edge.label(),
                        edge.traversed(), true)));
        return mermaid + runNodeClasses(nodes);
    }

    private WorkflowRunGraphNodeView buildRunNode(WorkflowNode node, RunGraphContext context) {
        String nodeType = normalizeType(node);
        String compositeRole = compositeRole(nodeType);
        String parallelGroup = context.parallelGroup(node.id());
        RunStepResponse step = context.latestStep(node.id());
        List<WorkflowRunGraphStepView> children = buildChildren(node, nodeType, context);
        Integer iterations = loopIterations(node, nodeType, context);
        boolean executed;
        StepStatus status;
        String stepId;
        String errorMessage;
        if ("loop_back".equals(nodeType)) {
            WorkflowRunGraphNodeView loopNode = context.loopNodeForLoopBack(node.id());
            boolean ranBody = loopNode != null && loopNode.iterations() != null && loopNode.iterations() > 0;
            executed = ranBody;
            status = ranBody && loopNode != null ? loopNode.status() : null;
            stepId = null;
            errorMessage = null;
        }
        else if (context.isCompositeScopedBodyNode(node.id(), nodeType)) {
            executed = false;
            status = null;
            stepId = null;
            errorMessage = null;
        }
        else {
            executed = step != null || !children.isEmpty();
            status = step == null ? null : step.status();
            stepId = step == null ? null : step.stepId();
            errorMessage = step == null ? null : step.errorMessage();
        }
        String statusLabel = status == null ? "NOT_EXECUTED" : status.name();
        return new WorkflowRunGraphNodeView(node.id(), node.type(),
                label(node.id(), node.type()) + " " + statusLabel,
                executed, status, stepId, errorMessage, compositeRole, parallelGroup, iterations, children);
    }

    private List<WorkflowRunGraphStepView> buildChildren(WorkflowNode node, String nodeType,
            RunGraphContext context) {
        return switch (nodeType) {
            case "dynamic" -> dynamicChildren(node.id(), context);
            case "subgraph" -> subgraphChildren(node.id(), context);
            case "loop" -> loopChildren(node.id(), context);
            case "parallel" -> parallelBranchChildren(node.id(), context);
            default -> List.of();
        };
    }

    private List<WorkflowRunGraphStepView> dynamicChildren(String dynamicNodeId, RunGraphContext context) {
        String prefix = dynamicNodeId + DYNAMIC_STEP_SEPARATOR;
        return context.stepsByNodeId().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .flatMap(entry -> entry.getValue().stream())
                .map(step -> toStepView(step, context))
                .toList();
    }

    private List<WorkflowRunGraphStepView> subgraphChildren(String subgraphNodeId, RunGraphContext context) {
        String prefix = subgraphNodeId + "::";
        return context.stepsByNodeId().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(prefix))
                .flatMap(entry -> entry.getValue().stream())
                .map(step -> toStepView(step, context))
                .toList();
    }

    private List<WorkflowRunGraphStepView> loopChildren(String loopNodeId, RunGraphContext context) {
        WorkflowLoopBlock loopBlock = context.executionPlan().loopBlock(loopNodeId);
        List<WorkflowRunGraphStepView> children = new ArrayList<>();
        for (String bodyNodeId : loopBlock.bodyNodeIds()) {
            for (RunStepResponse step : context.stepsForNode(bodyNodeId)) {
                children.add(toStepView(step, bodyNodeId, context));
            }
        }
        return List.copyOf(children);
    }

    private List<WorkflowRunGraphStepView> parallelBranchChildren(String parallelNodeId, RunGraphContext context) {
        WorkflowParallelBlock parallelBlock = context.executionPlan().parallelBlock(parallelNodeId);
        List<WorkflowRunGraphStepView> children = new ArrayList<>();
        for (WorkflowBranchPath branch : parallelBlock.branches()) {
            String syntheticId = syntheticBranchNodeId(parallelNodeId, branch.branchStartNodeId());
            boolean allExecuted = branch.nodeIds().stream().allMatch(context::hasStep);
            StepStatus status = branch.nodeIds().stream()
                    .map(context::latestStep)
                    .filter(Objects::nonNull)
                    .map(RunStepResponse::status)
                    .reduce(this::mergeStatus)
                    .orElse(allExecuted ? StepStatus.SUCCEEDED : null);
            children.add(new WorkflowRunGraphStepView(syntheticId, "parallel_branch", status, null, null));
            for (String branchNodeId : branch.nodeIds()) {
                RunStepResponse step = context.latestStep(branchNodeId);
                if (step != null) {
                    children.add(toStepView(step, branchNodeId, context));
                }
            }
        }
        return List.copyOf(children);
    }

    private Integer loopIterations(WorkflowNode node, String nodeType, RunGraphContext context) {
        if (!"loop".equals(nodeType)) {
            return null;
        }
        WorkflowLoopBlock loopBlock = context.executionPlan().loopBlock(node.id());
        if (loopBlock.bodyNodeIds().isEmpty()) {
            return 0;
        }
        String firstBodyNodeId = loopBlock.bodyNodeIds().getFirst();
        return context.stepsForNode(firstBodyNodeId).size();
    }

    private boolean isEdgeTraversed(WorkflowExecutionEdge edge, Map<String, WorkflowRunGraphNodeView> nodesById) {
        WorkflowRunGraphNodeView from = nodesById.get(edge.from());
        WorkflowRunGraphNodeView to = nodesById.get(edge.to());
        if (from == null || to == null) {
            return false;
        }
        if ("loop_back".equals(normalizeType(from.type())) && "loop".equals(normalizeType(to.type()))) {
            return from.executed() && to.executed();
        }
        if ("body".equals(edge.condition()) && "loop".equals(normalizeType(from.type()))) {
            return from.executed() && from.iterations() != null && from.iterations() > 0;
        }
        if ("exit".equals(edge.condition()) && "loop".equals(normalizeType(from.type()))) {
            return from.executed();
        }
        return from.executed() && to.executed();
    }

    private String buildAdvancedRunMermaid(WorkflowDefinition definition, WorkflowExecutionPlan executionPlan,
            List<WorkflowRunGraphNodeView> nodes, List<WorkflowRunGraphEdgeView> edges) {
        Map<String, WorkflowRunGraphNodeView> nodesById = nodes.stream()
                .collect(Collectors.toMap(WorkflowRunGraphNodeView::id, node -> node, (left, right) -> left,
                        LinkedHashMap::new));
        Set<String> clusteredNodeIds = new LinkedHashSet<>();
        StringBuilder builder = new StringBuilder("flowchart TD");
        Map<String, String> aliasesByNodeId = new LinkedHashMap<>();
        int aliasCounter = 0;
        for (WorkflowRunGraphNodeView node : nodes) {
            String alias = "n" + aliasCounter++;
            aliasesByNodeId.put(node.id(), alias);
            builder.append('\n')
                    .append("  ")
                    .append(alias)
                    .append("[\"")
                    .append(escapeMermaidLabel(node.label()))
                    .append("\"]");
        }
        AliasedMermaidBuilder mermaidBuilder = new AliasedMermaidBuilder(builder, aliasesByNodeId);
        MermaidClassCollector classCollector = new MermaidClassCollector();
        Map<String, StepStatus> stepStatusByNodeId = stepStatusByNodeId(nodes);
        for (WorkflowParallelBlock parallelBlock : executionPlan.parallelBlocks()) {
            appendParallelCluster(mermaidBuilder, parallelBlock, nodesById, clusteredNodeIds, aliasCounter,
                    classCollector, stepStatusByNodeId);
            aliasCounter += parallelBlock.branches().size();
        }
        for (WorkflowLoopBlock loopBlock : executionPlan.loopBlocks()) {
            appendLoopCluster(mermaidBuilder, loopBlock, nodesById, clusteredNodeIds, aliasCounter,
                    loopBackForLoop(definition, loopBlock), classCollector, stepStatusByNodeId);
            aliasCounter += loopBlock.bodyNodeIds().size() + 1;
        }
        for (WorkflowRunGraphEdgeView edge : edges) {
            if (clusteredNodeIds.contains(edge.from()) && clusteredNodeIds.contains(edge.to())) {
                continue;
            }
            appendEdge(mermaidBuilder, edge.from(), edge.to(), edge.label(), edge.traversed(), true);
        }
        appendCompositeChildNodes(mermaidBuilder, nodes, aliasCounter, classCollector);
        return builder.toString() + runNodeClasses(nodes) + classCollector.render(this);
    }

    private Map<String, StepStatus> stepStatusByNodeId(List<WorkflowRunGraphNodeView> nodes) {
        Map<String, StepStatus> statuses = new LinkedHashMap<>();
        for (WorkflowRunGraphNodeView node : nodes) {
            if (node.status() != null) {
                statuses.put(node.id(), node.status());
            }
            for (WorkflowRunGraphStepView child : node.children()) {
                if (child.status() != null) {
                    statuses.put(child.id(), child.status());
                }
            }
        }
        return statuses;
    }

    private void appendParallelCluster(AliasedMermaidBuilder builder, WorkflowParallelBlock parallelBlock,
            Map<String, WorkflowRunGraphNodeView> nodesById, Set<String> clusteredNodeIds, int aliasCounter,
            MermaidClassCollector classCollector, Map<String, StepStatus> stepStatusByNodeId) {
        String clusterId = "parallel_" + parallelBlock.parallelNodeId();
        builder.builder().append("\n  subgraph ").append(clusterId).append("[\"parallel ")
                .append(escapeMermaidLabel(parallelBlock.parallelNodeId()))
                .append("\"]");
        int branchAliasOffset = aliasCounter;
        for (WorkflowBranchPath branch : parallelBlock.branches()) {
            String syntheticId = syntheticBranchNodeId(parallelBlock.parallelNodeId(), branch.branchStartNodeId());
            String alias = "pb" + branchAliasOffset++;
            builder.aliasesByNodeId().put(syntheticId, alias);
            StepStatus branchStatus = branch.nodeIds().stream()
                    .map(stepStatusByNodeId::get)
                    .filter(Objects::nonNull)
                    .reduce(this::mergeStatus)
                    .orElse(null);
            classCollector.add(alias, branchStatus);
            builder.builder().append('\n')
                    .append("    ")
                    .append(alias)
                    .append("[\"")
                    .append(escapeMermaidLabel(syntheticId + " (parallel_branch)"))
                    .append("\"]");
            for (String branchNodeId : branch.nodeIds()) {
                clusteredNodeIds.add(branchNodeId);
            }
        }
        builder.builder().append("\n  end");
        WorkflowRunGraphNodeView parallelNode = nodesById.get(parallelBlock.parallelNodeId());
        if (parallelNode == null || builder.alias(parallelBlock.parallelNodeId()) == null) {
            return;
        }
        for (WorkflowBranchPath branch : parallelBlock.branches()) {
            String syntheticId = syntheticBranchNodeId(parallelBlock.parallelNodeId(), branch.branchStartNodeId());
            boolean branchExecuted = branch.nodeIds().stream()
                    .map(nodesById::get)
                    .filter(Objects::nonNull)
                    .allMatch(WorkflowRunGraphNodeView::executed);
            appendEdge(builder, parallelBlock.parallelNodeId(), syntheticId, null, parallelNode.executed(), true);
            appendEdge(builder, syntheticId, parallelBlock.joinNodeId(), null, branchExecuted, true);
        }
    }

    private void appendLoopCluster(AliasedMermaidBuilder builder, WorkflowLoopBlock loopBlock,
            Map<String, WorkflowRunGraphNodeView> nodesById, Set<String> clusteredNodeIds, int aliasCounter,
            String loopBackId, MermaidClassCollector classCollector, Map<String, StepStatus> stepStatusByNodeId) {
        String clusterId = "loop_" + loopBlock.loopNodeId();
        builder.builder().append("\n  subgraph ").append(clusterId).append("[\"loop body ")
                .append(escapeMermaidLabel(loopBlock.loopNodeId()))
                .append("\"]");
        int bodyAliasOffset = aliasCounter;
        for (String bodyNodeId : loopBlock.bodyNodeIds()) {
            clusteredNodeIds.add(bodyNodeId);
            WorkflowRunGraphNodeView bodyNode = nodesById.get(bodyNodeId);
            if (bodyNode == null) {
                continue;
            }
            String alias = "lb" + bodyAliasOffset++;
            builder.aliasesByNodeId().put(bodyNodeId + ":cluster", alias);
            classCollector.add(alias, stepStatusByNodeId.get(bodyNodeId));
            builder.builder().append('\n')
                    .append("    ")
                    .append(alias)
                    .append("[\"")
                    .append(escapeMermaidLabel(bodyNode.label()))
                    .append("\"]");
        }
        builder.builder().append("\n  end");
        if (loopBackId != null && builder.alias(loopBackId) != null && builder.alias(loopBlock.loopNodeId()) != null) {
            appendEdge(builder, loopBackId, loopBlock.loopNodeId(), "loop_back", true, true);
        }
    }

    private void appendCompositeChildNodes(AliasedMermaidBuilder builder, List<WorkflowRunGraphNodeView> nodes,
            int aliasCounter, MermaidClassCollector classCollector) {
        int childAlias = aliasCounter + 1000;
        for (WorkflowRunGraphNodeView node : nodes) {
            if (node.children().isEmpty() || builder.alias(node.id()) == null) {
                continue;
            }
            for (WorkflowRunGraphStepView child : node.children()) {
                if ("parallel_branch".equals(child.type())) {
                    continue;
                }
                String childAliasId = "c" + childAlias++;
                builder.aliasesByNodeId().put(child.id(), childAliasId);
                classCollector.add(childAliasId, child.status());
                String childLabel = child.id() + " (" + child.type() + ") "
                        + (child.status() == null ? "NOT_EXECUTED" : child.status().name());
                builder.builder().append('\n')
                        .append("  ")
                        .append(childAliasId)
                        .append("[\"")
                        .append(escapeMermaidLabel(childLabel))
                        .append("\"]");
                appendEdge(builder, node.id(), child.id(), null, child.status() != null, true);
            }
        }
    }

    private String loopBackForLoop(WorkflowDefinition definition, WorkflowLoopBlock loopBlock) {
        for (WorkflowEdge edge : definition.edges()) {
            WorkflowNode from = definition.nodes().stream()
                    .filter(node -> node.id().equals(edge.from()))
                    .findFirst()
                    .orElse(null);
            WorkflowNode to = definition.nodes().stream()
                    .filter(node -> node.id().equals(edge.to()))
                    .findFirst()
                    .orElse(null);
            if (from != null && to != null && "loop_back".equalsIgnoreCase(from.type())
                    && loopBlock.loopNodeId().equals(to.id())) {
                return from.id();
            }
        }
        return null;
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

    private WorkflowRunGraphStepView toStepView(RunStepResponse step, RunGraphContext context) {
        String nodeId = normalizeStepNodeName(step.nodeName());
        return toStepView(step, nodeId, context);
    }

    private WorkflowRunGraphStepView toStepView(RunStepResponse step, String nodeId, RunGraphContext context) {
        String type = inferStepType(nodeId, context);
        return new WorkflowRunGraphStepView(nodeId, type, step.status(), step.stepId(), step.errorMessage());
    }

    private String inferStepType(String nodeId, RunGraphContext context) {
        String mappedType = context.stepType(nodeId);
        if (mappedType != null) {
            return mappedType;
        }
        if (nodeId.contains(DYNAMIC_STEP_SEPARATOR)) {
            return "tool";
        }
        if (nodeId.contains("::")) {
            return "nested";
        }
        return "step";
    }

    private String inferStepType(String nodeId) {
        if (nodeId.contains(DYNAMIC_STEP_SEPARATOR)) {
            return "tool";
        }
        if (nodeId.contains("::")) {
            return "nested";
        }
        return "step";
    }

    private StepStatus mergeStatus(StepStatus left, StepStatus right) {
        if (left == StepStatus.FAILED || right == StepStatus.FAILED) {
            return StepStatus.FAILED;
        }
        if (left == StepStatus.RUNNING || right == StepStatus.RUNNING) {
            return StepStatus.RUNNING;
        }
        return StepStatus.SUCCEEDED;
    }

    private String syntheticBranchNodeId(String parallelNodeId, String branchStartNodeId) {
        return "workflow_branch_" + parallelNodeId + "_" + branchStartNodeId;
    }

    private boolean hasAdvancedStructure(WorkflowDefinition definition, WorkflowExecutionPlan executionPlan) {
        if (!executionPlan.loopBlocks().isEmpty() || !executionPlan.parallelBlocks().isEmpty()) {
            return true;
        }
        return definition.nodes().stream().anyMatch(node -> {
            String type = normalizeType(node.type());
            return "dynamic".equals(type) || "subgraph".equals(type);
        });
    }

    private Map<String, RunStepResponse> latestStepsByNodeName(List<RunStepResponse> steps) {
        Map<String, RunStepResponse> stepsByNodeName = new LinkedHashMap<>();
        for (RunStepResponse step : steps) {
            stepsByNodeName.put(normalizeStepNodeName(step.nodeName()), step);
        }
        return stepsByNodeName;
    }

    private String normalizeStepNodeName(String nodeName) {
        if (nodeName != null && nodeName.startsWith("workflow_node_")) {
            return nodeName.substring("workflow_node_".length());
        }
        return nodeName;
    }

    private String compositeRole(String nodeType) {
        return switch (nodeType) {
            case "loop" -> "LOOP";
            case "subgraph" -> "SUBGRAPH";
            case "dynamic" -> "DYNAMIC";
            case "loop_back" -> "LOOP_BACK";
            case "parallel" -> "PARALLEL";
            case "join" -> "JOIN";
            default -> null;
        };
    }

    private String normalizeType(WorkflowNode node) {
        return node.type() == null ? "" : node.type().toLowerCase();
    }

    private String normalizeType(String nodeType) {
        return nodeType == null ? "" : nodeType.toLowerCase();
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
        if (builder.alias(from) == null || builder.alias(to) == null) {
            return;
        }
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

    private static final class RunGraphContext {

        private final WorkflowDefinition definition;
        private final WorkflowExecutionPlan executionPlan;
        private final Map<String, List<RunStepResponse>> stepsByNodeId;
        private final Map<String, RunStepResponse> latestStepByNodeId;
        private final Map<String, String> parallelGroupByNodeId;
        private final Map<String, String> loopBackToLoopNodeId;
        private final Map<String, String> stepTypesByNodeId;
        private final Map<String, WorkflowRunGraphNodeView> builtLoopNodes = new LinkedHashMap<>();

        private RunGraphContext(WorkflowDefinition definition, WorkflowExecutionPlan executionPlan,
                List<RunStepResponse> steps, Map<String, String> stepTypesByNodeId) {
            this.definition = definition;
            this.executionPlan = executionPlan;
            this.stepsByNodeId = groupSteps(steps);
            this.latestStepByNodeId = latestSteps(steps);
            this.parallelGroupByNodeId = buildParallelGroups(executionPlan);
            this.loopBackToLoopNodeId = buildLoopBackMappings(definition);
            this.stepTypesByNodeId = stepTypesByNodeId == null ? Map.of() : Map.copyOf(stepTypesByNodeId);
        }

        private WorkflowDefinition definition() {
            return definition;
        }

        private WorkflowExecutionPlan executionPlan() {
            return executionPlan;
        }

        private Map<String, List<RunStepResponse>> stepsByNodeId() {
            return stepsByNodeId;
        }

        private List<RunStepResponse> stepsForNode(String nodeId) {
            return stepsByNodeId.getOrDefault(nodeId, List.of());
        }

        private RunStepResponse latestStep(String nodeId) {
            return latestStepByNodeId.get(nodeId);
        }

        private boolean hasStep(String nodeId) {
            return latestStepByNodeId.containsKey(nodeId);
        }

        private String parallelGroup(String nodeId) {
            return parallelGroupByNodeId.get(nodeId);
        }

        private String stepType(String nodeId) {
            return stepTypesByNodeId.get(nodeId);
        }

        private boolean isCompositeScopedBodyNode(String nodeId, String nodeType) {
            return !"loop_back".equals(nodeType) && executionPlan.isCompositeScopedNode(nodeId);
        }

        private WorkflowRunGraphNodeView loopNodeForLoopBack(String loopBackNodeId) {
            String loopNodeId = loopBackToLoopNodeId.get(loopBackNodeId);
            if (loopNodeId == null) {
                return null;
            }
            return builtLoopNodes.computeIfAbsent(loopNodeId, id -> {
                WorkflowNode loopNode = definition.nodes().stream()
                        .filter(node -> node.id().equals(id))
                        .findFirst()
                        .orElse(null);
                if (loopNode == null) {
                    return null;
                }
                return buildLoopNodeSnapshot(loopNode);
            });
        }

        private WorkflowRunGraphNodeView buildLoopNodeSnapshot(WorkflowNode loopNode) {
            RunStepResponse step = latestStep(loopNode.id());
            Integer iterations = loopIterations(loopNode.id());
            boolean executed = step != null || iterations != null && iterations > 0;
            StepStatus status = step == null ? null : step.status();
            String statusLabel = status == null ? "NOT_EXECUTED" : status.name();
            return new WorkflowRunGraphNodeView(loopNode.id(), loopNode.type(),
                    loopNode.id() + " (" + loopNode.type() + ") " + statusLabel,
                    executed, status, step == null ? null : step.stepId(),
                    step == null ? null : step.errorMessage(),
                    "LOOP", null, iterations, List.of());
        }

        private Integer loopIterations(String loopNodeId) {
            WorkflowLoopBlock loopBlock = executionPlan.loopBlock(loopNodeId);
            if (loopBlock.bodyNodeIds().isEmpty()) {
                return 0;
            }
            return stepsForNode(loopBlock.bodyNodeIds().getFirst()).size();
        }

        private static Map<String, List<RunStepResponse>> groupSteps(List<RunStepResponse> steps) {
            Map<String, List<RunStepResponse>> grouped = new LinkedHashMap<>();
            for (RunStepResponse step : steps) {
                String nodeId = normalizeStepNodeName(step.nodeName());
                grouped.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(step);
            }
            return grouped;
        }

        private static Map<String, RunStepResponse> latestSteps(List<RunStepResponse> steps) {
            Map<String, RunStepResponse> latest = new LinkedHashMap<>();
            for (RunStepResponse step : steps) {
                latest.put(normalizeStepNodeName(step.nodeName()), step);
            }
            return latest;
        }

        private static Map<String, String> buildParallelGroups(WorkflowExecutionPlan executionPlan) {
            Map<String, String> groups = new LinkedHashMap<>();
            for (WorkflowParallelBlock block : executionPlan.parallelBlocks()) {
                groups.put(block.parallelNodeId(), block.parallelNodeId());
                groups.put(block.joinNodeId(), block.parallelNodeId());
                for (WorkflowBranchPath branch : block.branches()) {
                    for (String nodeId : branch.nodeIds()) {
                        groups.put(nodeId, block.parallelNodeId());
                    }
                }
            }
            return groups;
        }

        private static Map<String, String> buildLoopBackMappings(WorkflowDefinition definition) {
            Map<String, String> mappings = new LinkedHashMap<>();
            for (WorkflowEdge edge : definition.edges()) {
                WorkflowNode from = definition.nodes().stream()
                        .filter(node -> node.id().equals(edge.from()))
                        .findFirst()
                        .orElse(null);
                WorkflowNode to = definition.nodes().stream()
                        .filter(node -> node.id().equals(edge.to()))
                        .findFirst()
                        .orElse(null);
                if (from != null && to != null && "loop_back".equalsIgnoreCase(from.type())
                        && "loop".equalsIgnoreCase(to.type())) {
                    mappings.put(from.id(), to.id());
                }
            }
            return mappings;
        }

        private static String normalizeStepNodeName(String nodeName) {
            if (nodeName != null && nodeName.startsWith("workflow_node_")) {
                return nodeName.substring("workflow_node_".length());
            }
            return nodeName;
        }

    }

    private static final class MermaidClassCollector {

        private final Map<String, StepStatus> aliasStatuses = new LinkedHashMap<>();

        private void add(String alias, StepStatus status) {
            aliasStatuses.put(alias, status);
        }

        private String render(WorkflowGraphRenderer renderer) {
            if (aliasStatuses.isEmpty()) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<String, StepStatus> entry : aliasStatuses.entrySet()) {
                builder.append('\n')
                        .append("  class ")
                        .append(entry.getKey())
                        .append(' ')
                        .append(renderer.nodeClass(entry.getValue()));
            }
            return builder.toString();
        }

    }

    private record AliasedMermaidBuilder(StringBuilder builder, Map<String, String> aliasesByNodeId) {

        String alias(String nodeId) {
            return aliasesByNodeId.get(nodeId);
        }

    }

}
