package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class WorkflowCompiler {

    private static final Set<String> SUPPORTED_TYPES = Set.of("start", "retriever", "llm", "tool", "condition",
            "parallel", "join", "end", "loop", "loop_back", "subgraph", "dynamic");
    private static final Set<String> SUPPORTED_EDGE_CONDITIONS = Set.of("true", "false", "body", "exit");

    private final WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry;
    private final WorkflowRuntimeProperties workflowRuntimeProperties;

    public WorkflowCompiler(WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry) {
        this(workflowNodeSchemaRegistry, new WorkflowRuntimeProperties());
    }

    @Autowired
    public WorkflowCompiler(WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry,
            WorkflowRuntimeProperties workflowRuntimeProperties) {
        this.workflowNodeSchemaRegistry = workflowNodeSchemaRegistry;
        this.workflowRuntimeProperties = workflowRuntimeProperties;
    }

    public WorkflowExecutionPlan compile(WorkflowDefinition definition) {
        validateDefinitionBudget(definition);
        Map<String, WorkflowNode> nodesById = indexNodes(definition.nodes());
        WorkflowNode start = singleNodeByType(nodesById, "start");
        WorkflowNode end = singleNodeByType(nodesById, "end");
        EdgeIndex edgeIndex = indexEdges(definition.edges(), nodesById);
        List<WorkflowParallelBlock> parallelBlocks = validateSupportedTopology(start, end, nodesById, edgeIndex);
        List<WorkflowLoopBlock> loopBlocks = validateLoopBlocks(nodesById, edgeIndex);
        Set<String> compositeScopedNodeIds = compositeScopedNodeIds(loopBlocks, nodesById);
        List<WorkflowNode> linearNodes = isLinear(edgeIndex)
                ? orderLinearPath(start, end, nodesById, edgeIndex)
                : List.of();
        return new WorkflowExecutionPlan(start, end, nodesById, edgeIndex.outgoing(), edgeIndex.incoming(),
                linearNodes, parallelBlocks, loopBlocks, compositeScopedNodeIds);
    }

    private void validateDefinitionBudget(WorkflowDefinition definition) {
        if (definition.nodes().size() > workflowRuntimeProperties.getMaxNodes()) {
            throw new BusinessException("WORKFLOW_BUDGET_EXCEEDED",
                    "Workflow node count exceeds limit of " + workflowRuntimeProperties.getMaxNodes());
        }
        if (definition.edges().size() > workflowRuntimeProperties.getMaxEdges()) {
            throw new BusinessException("WORKFLOW_BUDGET_EXCEEDED",
                    "Workflow edge count exceeds limit of " + workflowRuntimeProperties.getMaxEdges());
        }
    }

    private Map<String, WorkflowNode> indexNodes(List<WorkflowNode> nodes) {
        Map<String, WorkflowNode> nodesById = new LinkedHashMap<>();
        for (WorkflowNode node : nodes) {
            if (nodesById.containsKey(node.id())) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED", "Duplicate node id: " + node.id());
            }
            String type = normalizeType(node);
            if (!SUPPORTED_TYPES.contains(type)) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED", "Unsupported node type: " + node.type());
            }
            validateConfig(node, type);
            nodesById.put(node.id(), node);
        }
        return nodesById;
    }

    private void validateConfig(WorkflowNode node, String normalizedType) {
        WorkflowNodeSchema schema = workflowNodeSchemaRegistry.findSchema(normalizedType)
                .orElseThrow(() -> new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Missing workflow node schema for type: " + normalizedType));
        Map<String, WorkflowNodeConfigField> fieldsByName = schema.configFields().stream()
                .collect(Collectors.toMap(WorkflowNodeConfigField::name, Function.identity()));
        for (WorkflowNodeConfigField field : schema.configFields()) {
            if (field.required() && !node.config().containsKey(field.name())) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Config " + node.id() + "." + field.name() + " is required");
            }
        }
        for (Map.Entry<String, Object> configEntry : node.config().entrySet()) {
            WorkflowNodeConfigField field = fieldsByName.get(configEntry.getKey());
            if (field == null) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Unsupported config key for node " + node.id() + ": " + configEntry.getKey());
            }
            validateConfigField(node, field, configEntry.getValue());
        }
    }

    private void validateConfigField(WorkflowNode node, WorkflowNodeConfigField field, Object value) {
        if (value == null) {
            if (field.required()) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Config " + node.id() + "." + field.name() + " is required");
            }
            return;
        }
        if (!matchesType(field.type(), value)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + "." + field.name() + " must be " + field.type());
        }
        if (value instanceof String text && !StringUtils.hasText(text)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + "." + field.name() + " must not be blank");
        }
        validateNumberRange(node, field, value);
        validateAllowedValues(node, field, value);
    }

    private void validateAllowedValues(WorkflowNode node, WorkflowNodeConfigField field, Object value) {
        Object allowedValues = field.constraints().get("allowedValues");
        if (!(allowedValues instanceof Iterable<?> iterable) || !(value instanceof String text)) {
            return;
        }
        for (Object allowedValue : iterable) {
            if (String.valueOf(allowedValue).equalsIgnoreCase(text)) {
                return;
            }
        }
        throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                "Config " + node.id() + "." + field.name() + " must be one of " + allowedValues);
    }

    private boolean matchesType(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof Iterable<?> || value.getClass().isArray();
            default -> true;
        };
    }

    private void validateNumberRange(WorkflowNode node, WorkflowNodeConfigField field, Object value) {
        if (!(value instanceof Number number)) {
            return;
        }
        Optional<Number> min = numberConstraint(field, "min");
        if (min.isPresent() && number.doubleValue() < min.get().doubleValue()) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + "." + field.name() + " must be >= " + min.get());
        }
        Optional<Number> max = numberConstraint(field, "max");
        if (max.isPresent() && number.doubleValue() > max.get().doubleValue()) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + "." + field.name() + " must be <= " + max.get());
        }
    }

    private Optional<Number> numberConstraint(WorkflowNodeConfigField field, String key) {
        Object value = field.constraints().get(key);
        if (value instanceof Number number) {
            return Optional.of(number);
        }
        return Optional.empty();
    }

    private WorkflowNode singleNodeByType(Map<String, WorkflowNode> nodesById, String type) {
        List<WorkflowNode> matches = nodesById.values()
                .stream()
                .filter(node -> type.equals(normalizeType(node)))
                .toList();
        if (matches.isEmpty()) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED", "Workflow must contain a " + type + " node");
        }
        if (matches.size() > 1) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED", "Only one " + type + " node is supported");
        }
        return matches.getFirst();
    }

    private EdgeIndex indexEdges(List<WorkflowEdge> edges, Map<String, WorkflowNode> nodesById) {
        Map<String, List<WorkflowExecutionEdge>> outgoing = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();
        Set<String> uniqueEdges = new HashSet<>();
        for (WorkflowEdge edge : edges) {
            if (!nodesById.containsKey(edge.from()) || !nodesById.containsKey(edge.to())) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Edge references missing node: " + edge.from() + " -> " + edge.to());
            }
            if (edge.from().equals(edge.to())) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED", "Self-loop edges are not supported: " + edge.from());
            }
            String edgeKey = edge.from() + "->" + edge.to();
            if (!uniqueEdges.add(edgeKey)) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED", "Duplicate edge is not supported: " + edgeKey);
            }
            WorkflowExecutionEdge executionEdge = new WorkflowExecutionEdge(edge);
            validateEdgeCondition(executionEdge);
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(executionEdge);
            incoming.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.from());
        }
        return new EdgeIndex(outgoing, incoming);
    }

    private void validateEdgeCondition(WorkflowExecutionEdge edge) {
        if (edge.conditional() && !SUPPORTED_EDGE_CONDITIONS.contains(edge.condition())) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Unsupported edge condition " + edge.condition() + " on " + edge.from() + " -> " + edge.to());
        }
    }

    private List<WorkflowParallelBlock> validateSupportedTopology(WorkflowNode start, WorkflowNode end,
            Map<String, WorkflowNode> nodesById,
            EdgeIndex edgeIndex) {
        validateNodeDegrees(start, end, nodesById, edgeIndex);
        validateReachability(start, end, nodesById, edgeIndex);
        return validateParallelJoinBlocks(nodesById, edgeIndex);
    }

    private void validateNodeDegrees(WorkflowNode start, WorkflowNode end, Map<String, WorkflowNode> nodesById,
            EdgeIndex edgeIndex) {
        for (WorkflowNode node : nodesById.values()) {
            List<WorkflowExecutionEdge> outgoing = edgeIndex.outgoing().getOrDefault(node.id(), List.of());
            int outDegree = outgoing.size();
            int inDegree = edgeIndex.incoming().getOrDefault(node.id(), List.of()).size();
            String type = normalizeType(node);
            if (!node.id().equals(end.id()) && outDegree == 0) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Every non-end workflow node must have an outgoing edge: " + node.id());
            }
            if (!node.id().equals(start.id()) && inDegree == 0) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Every non-start workflow node must have an incoming edge: " + node.id());
            }
            switch (type) {
                case "condition" -> validateConditionNodeEdges(node, outgoing);
                case "parallel" -> validateParallelNodeEdges(node, outgoing);
                case "join" -> validateJoinNodeEdges(node, outgoing, inDegree);
                case "loop" -> validateLoopNodeEdges(node, outgoing);
                case "loop_back" -> validateLoopBackNodeEdges(node, outgoing, nodesById);
                default -> validateSingleOutgoingNodeEdges(node, outgoing);
            }
        }
        if (!edgeIndex.incoming().getOrDefault(start.id(), List.of()).isEmpty()) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED", "Start node cannot have incoming edges");
        }
        if (!edgeIndex.outgoing().getOrDefault(end.id(), List.of()).isEmpty()) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED", "End node cannot have outgoing edges");
        }
    }

    private void validateConditionNodeEdges(WorkflowNode node, List<WorkflowExecutionEdge> outgoing) {
        if (outgoing.size() != 2) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Condition node must have exactly true and false outgoing edges: " + node.id());
        }
        Set<String> conditions = outgoing.stream()
                .map(WorkflowExecutionEdge::condition)
                .collect(Collectors.toSet());
        if (!conditions.equals(Set.of("true", "false"))) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Condition node outgoing edges must use condition=true and condition=false: " + node.id());
        }
    }

    private void validateParallelNodeEdges(WorkflowNode node, List<WorkflowExecutionEdge> outgoing) {
        if (outgoing.size() < 2) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Parallel node must have at least two outgoing edges: " + node.id());
        }
        if (outgoing.size() > workflowRuntimeProperties.getMaxParallelBranches()) {
            throw new BusinessException("WORKFLOW_BUDGET_EXCEEDED",
                    "Parallel branch count exceeds limit of "
                            + workflowRuntimeProperties.getMaxParallelBranches() + ": " + node.id());
        }
        if (outgoing.stream().anyMatch(WorkflowExecutionEdge::conditional)) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Parallel node outgoing edges cannot define conditions: " + node.id());
        }
    }

    private void validateJoinNodeEdges(WorkflowNode node, List<WorkflowExecutionEdge> outgoing, int inDegree) {
        if (inDegree < 2) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Join node must have at least two incoming edges: " + node.id());
        }
        validateSingleOutgoingNodeEdges(node, outgoing);
    }

    private void validateSingleOutgoingNodeEdges(WorkflowNode node, List<WorkflowExecutionEdge> outgoing) {
        if (outgoing.size() > 1) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Only condition or parallel nodes can branch: " + node.id());
        }
        if (outgoing.stream().anyMatch(WorkflowExecutionEdge::conditional)) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Only condition node outgoing edges can define conditions: " + node.id());
        }
    }

    private void validateReachability(WorkflowNode start, WorkflowNode end, Map<String, WorkflowNode> nodesById,
            EdgeIndex edgeIndex) {
        Set<String> reachableFromStart = traverseFrom(start.id(), edgeIndex.outgoing());
        if (!reachableFromStart.containsAll(nodesById.keySet())) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow nodes must be reachable from start");
        }
        Set<String> nodesReachingEnd = traverseReverseFrom(end.id(), edgeIndex.incoming());
        if (!nodesReachingEnd.containsAll(nodesById.keySet())) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow nodes must eventually reach end");
        }
        detectCycles(start.id(), edgeIndex.outgoing(), new HashSet<>(), new HashSet<>(), allowedLoopBackEdges(nodesById));
    }

    private Set<String> allowedLoopBackEdges(Map<String, WorkflowNode> nodesById) {
        Set<String> allowed = new HashSet<>();
        for (WorkflowNode node : nodesById.values()) {
            if ("loop_back".equals(normalizeType(node))) {
                allowed.add(node.id());
            }
        }
        return allowed;
    }

    private List<WorkflowLoopBlock> validateLoopBlocks(Map<String, WorkflowNode> nodesById, EdgeIndex edgeIndex) {
        List<WorkflowLoopBlock> loopBlocks = new ArrayList<>();
        for (WorkflowNode node : nodesById.values()) {
            if (!"loop".equals(normalizeType(node))) {
                continue;
            }
            WorkflowExecutionEdge bodyEdge = findEdgeWithCondition(edgeIndex, node.id(), "body");
            WorkflowExecutionEdge exitEdge = findEdgeWithCondition(edgeIndex, node.id(), "exit");
            List<String> bodyNodeIds = collectLoopBodyNodeIds(bodyEdge.to(), node.id(), nodesById, edgeIndex);
            int maxIterations = resolveMaxIterations(node);
            loopBlocks.add(new WorkflowLoopBlock(node.id(), exitEdge.to(), bodyNodeIds, maxIterations));
        }
        return List.copyOf(loopBlocks);
    }

    private int resolveMaxIterations(WorkflowNode loopNode) {
        Object value = loopNode.config().get("maxIterations");
        int maxIterations = 10;
        if (value instanceof Number number) {
            maxIterations = number.intValue();
        }
        else if (value != null) {
            maxIterations = Integer.parseInt(String.valueOf(value));
        }
        if (maxIterations < 1 || maxIterations > 50) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Loop maxIterations must be between 1 and 50: " + loopNode.id());
        }
        return maxIterations;
    }

    private List<String> collectLoopBodyNodeIds(String bodyStartNodeId, String loopNodeId,
            Map<String, WorkflowNode> nodesById, EdgeIndex edgeIndex) {
        List<String> bodyNodeIds = new ArrayList<>();
        String currentId = bodyStartNodeId;
        Set<String> visited = new HashSet<>();
        while (true) {
            WorkflowNode current = nodesById.get(currentId);
            if (current == null) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Loop body references missing node: " + currentId);
            }
            String type = normalizeType(current);
            if ("loop_back".equals(type)) {
                validateLoopBackTarget(current, loopNodeId, edgeIndex);
                return List.copyOf(bodyNodeIds);
            }
            if (!visited.add(currentId)) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Loop body must be linear before loop_back: " + currentId);
            }
            if ("loop".equals(type) || "parallel".equals(type) || "condition".equals(type)
                    || "subgraph".equals(type) || "dynamic".equals(type) || "loop_back".equals(type)) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Loop body cannot contain branching or composite nodes: " + currentId);
            }
            bodyNodeIds.add(currentId);
            List<WorkflowExecutionEdge> outgoing = edgeIndex.outgoing().getOrDefault(currentId, List.of());
            if (outgoing.size() != 1 || outgoing.getFirst().conditional()) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Loop body nodes must have exactly one unconditional outgoing edge: " + currentId);
            }
            currentId = outgoing.getFirst().to();
        }
    }

    private void validateLoopBackTarget(WorkflowNode loopBackNode, String expectedLoopNodeId, EdgeIndex edgeIndex) {
        List<WorkflowExecutionEdge> outgoing = edgeIndex.outgoing().getOrDefault(loopBackNode.id(), List.of());
        if (outgoing.size() != 1 || !expectedLoopNodeId.equals(outgoing.getFirst().to())) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "loop_back node must point back to its loop node: " + loopBackNode.id());
        }
    }

    private WorkflowExecutionEdge findEdgeWithCondition(EdgeIndex edgeIndex, String fromNodeId, String condition) {
        return edgeIndex.outgoing().getOrDefault(fromNodeId, List.of()).stream()
                .filter(edge -> condition.equals(edge.condition()))
                .findFirst()
                .orElseThrow(() -> new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Loop node must define outgoing edge condition=" + condition + ": " + fromNodeId));
    }

    private Set<String> compositeScopedNodeIds(List<WorkflowLoopBlock> loopBlocks,
            Map<String, WorkflowNode> nodesById) {
        Set<String> scoped = new HashSet<>();
        for (WorkflowLoopBlock block : loopBlocks) {
            scoped.addAll(block.bodyNodeIds());
        }
        for (WorkflowNode node : nodesById.values()) {
            if ("loop_back".equals(normalizeType(node))) {
                scoped.add(node.id());
            }
        }
        return Set.copyOf(scoped);
    }

    private void validateLoopNodeEdges(WorkflowNode node, List<WorkflowExecutionEdge> outgoing) {
        if (outgoing.size() != 2) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Loop node must have body and exit outgoing edges: " + node.id());
        }
        Set<String> conditions = outgoing.stream()
                .map(WorkflowExecutionEdge::condition)
                .collect(Collectors.toSet());
        if (!conditions.equals(Set.of("body", "exit"))) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Loop node outgoing edges must use condition=body and condition=exit: " + node.id());
        }
    }

    private void validateLoopBackNodeEdges(WorkflowNode node, List<WorkflowExecutionEdge> outgoing,
            Map<String, WorkflowNode> nodesById) {
        if (outgoing.size() != 1) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "loop_back node must have exactly one outgoing edge: " + node.id());
        }
        if (outgoing.getFirst().conditional()) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "loop_back outgoing edge cannot define a condition: " + node.id());
        }
        WorkflowNode target = nodesById.get(outgoing.getFirst().to());
        if (target == null || !"loop".equals(normalizeType(target))) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "loop_back node must point to a loop node: " + node.id());
        }
    }

    private List<WorkflowParallelBlock> validateParallelJoinBlocks(Map<String, WorkflowNode> nodesById,
            EdgeIndex edgeIndex) {
        Set<String> joinNodesReachedByParallel = new HashSet<>();
        List<WorkflowParallelBlock> parallelBlocks = new ArrayList<>();
        for (WorkflowNode node : nodesById.values()) {
            if (!"parallel".equals(normalizeType(node))) {
                continue;
            }
            List<WorkflowExecutionEdge> outgoing = edgeIndex.outgoing().getOrDefault(node.id(), List.of());
            String commonJoinId = null;
            Set<String> branchNodeIds = new HashSet<>();
            List<WorkflowBranchPath> branchPaths = new ArrayList<>();
            for (WorkflowExecutionEdge edge : outgoing) {
                WorkflowBranchPath path = findParallelBranchPath(edge.to(), nodesById, edgeIndex);
                if (commonJoinId == null) {
                    commonJoinId = path.joinNodeId();
                }
                else if (!commonJoinId.equals(path.joinNodeId())) {
                    throw new BusinessException("WORKFLOW_UNSUPPORTED",
                            "Parallel branches must converge to the same join node: " + node.id());
                }
                for (String branchNodeId : path.nodeIds()) {
                    if (!branchNodeIds.add(branchNodeId)) {
                        throw new BusinessException("WORKFLOW_UNSUPPORTED",
                                "Parallel branches cannot share intermediate nodes: " + node.id());
                    }
                }
                branchPaths.add(path);
            }
            if (commonJoinId != null
                    && edgeIndex.incoming().getOrDefault(commonJoinId, List.of()).size() != outgoing.size()) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Join node can only receive edges from one parallel block: " + commonJoinId);
            }
            if (commonJoinId != null) {
                joinNodesReachedByParallel.add(commonJoinId);
                parallelBlocks.add(new WorkflowParallelBlock(node.id(), commonJoinId, branchPaths));
            }
        }
        for (WorkflowNode node : nodesById.values()) {
            if ("join".equals(normalizeType(node)) && !joinNodesReachedByParallel.contains(node.id())) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Join node must be reached by a parallel node: " + node.id());
            }
        }
        return List.copyOf(parallelBlocks);
    }

    private WorkflowBranchPath findParallelBranchPath(String branchStartNodeId, Map<String, WorkflowNode> nodesById,
            EdgeIndex edgeIndex) {
        List<String> branchNodeIds = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        WorkflowNode current = nodesById.get(branchStartNodeId);
        while (current != null) {
            if (!visited.add(current.id())) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow cycles are not supported");
            }
            String type = normalizeType(current);
            if ("join".equals(type)) {
                return new WorkflowBranchPath(branchStartNodeId, current.id(), branchNodeIds);
            }
            if ("condition".equals(type) || "parallel".equals(type) || "loop".equals(type)
                    || "subgraph".equals(type) || "dynamic".equals(type) || "loop_back".equals(type)) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Parallel branches only support linear nodes before join: " + current.id());
            }
            if ("end".equals(type)) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Parallel branch must reach a join node before end: " + branchStartNodeId);
            }
            branchNodeIds.add(current.id());
            List<WorkflowExecutionEdge> outgoing = edgeIndex.outgoing().getOrDefault(current.id(), List.of());
            if (outgoing.size() != 1 || outgoing.getFirst().conditional()) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Parallel branch must be linear before join: " + current.id());
            }
            current = nodesById.get(outgoing.getFirst().to());
        }
        throw new BusinessException("WORKFLOW_UNSUPPORTED",
                "Parallel branch references missing node: " + branchStartNodeId);
    }

    private Set<String> traverseFrom(String startId, Map<String, List<WorkflowExecutionEdge>> outgoing) {
        Set<String> visited = new HashSet<>();
        ArrayList<String> stack = new ArrayList<>();
        stack.add(startId);
        while (!stack.isEmpty()) {
            String current = stack.removeLast();
            if (!visited.add(current)) {
                continue;
            }
            for (WorkflowExecutionEdge edge : outgoing.getOrDefault(current, List.of())) {
                stack.add(edge.to());
            }
        }
        return visited;
    }

    private Set<String> traverseReverseFrom(String endId, Map<String, List<String>> incoming) {
        Set<String> visited = new HashSet<>();
        ArrayList<String> stack = new ArrayList<>();
        stack.add(endId);
        while (!stack.isEmpty()) {
            String current = stack.removeLast();
            if (!visited.add(current)) {
                continue;
            }
            stack.addAll(incoming.getOrDefault(current, List.of()));
        }
        return visited;
    }

    private void detectCycles(String nodeId, Map<String, List<WorkflowExecutionEdge>> outgoing, Set<String> visiting,
            Set<String> visited, Set<String> allowedLoopBackNodeIds) {
        if (visited.contains(nodeId)) {
            return;
        }
        if (!visiting.add(nodeId)) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow cycles are not supported");
        }
        for (WorkflowExecutionEdge edge : outgoing.getOrDefault(nodeId, List.of())) {
            if (allowedLoopBackNodeIds.contains(nodeId)) {
                continue;
            }
            detectCycles(edge.to(), outgoing, visiting, visited, allowedLoopBackNodeIds);
        }
        visiting.remove(nodeId);
        visited.add(nodeId);
    }

    private boolean isLinear(EdgeIndex edgeIndex) {
        return edgeIndex.outgoing().values().stream()
                .allMatch(edges -> edges.size() <= 1 && edges.stream().noneMatch(WorkflowExecutionEdge::conditional));
    }

    private List<WorkflowNode> orderLinearPath(WorkflowNode start, WorkflowNode end, Map<String, WorkflowNode> nodesById,
            EdgeIndex edgeIndex) {
        List<WorkflowNode> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        WorkflowNode current = start;
        while (current != null) {
            if (!visited.add(current.id())) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow cycles are not supported");
            }
            ordered.add(current);
            if (current.id().equals(end.id())) {
                break;
            }
            List<WorkflowExecutionEdge> nextEdges = edgeIndex.outgoing().getOrDefault(current.id(), List.of());
            if (nextEdges.size() != 1) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Workflow must be a single path from start to end");
            }
            current = nodesById.get(nextEdges.getFirst().to());
        }
        if (!ordered.getLast().id().equals(end.id()) || visited.size() != nodesById.size()) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Workflow must be one connected linear path from start to end");
        }
        return ordered;
    }

    private String normalizeType(WorkflowNode node) {
        return StringUtils.hasText(node.type()) ? node.type().toLowerCase(Locale.ROOT) : "";
    }

    private record EdgeIndex(Map<String, List<WorkflowExecutionEdge>> outgoing, Map<String, List<String>> incoming) {

        private EdgeIndex {
            outgoing = Collections.unmodifiableMap(outgoing);
            incoming = Collections.unmodifiableMap(incoming);
        }
    }

}
