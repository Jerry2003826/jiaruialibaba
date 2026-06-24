package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
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

    private static final Set<String> SUPPORTED_TYPES = Set.of("start", "retriever", "llm", "tool", "condition", "end");
    private static final Set<String> SUPPORTED_EDGE_CONDITIONS = Set.of("true", "false");

    private final WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry;

    public WorkflowCompiler(WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry) {
        this.workflowNodeSchemaRegistry = workflowNodeSchemaRegistry;
    }

    public WorkflowExecutionPlan compile(WorkflowDefinition definition) {
        Map<String, WorkflowNode> nodesById = indexNodes(definition.nodes());
        WorkflowNode start = singleNodeByType(nodesById, "start");
        WorkflowNode end = singleNodeByType(nodesById, "end");
        EdgeIndex edgeIndex = indexEdges(definition.edges(), nodesById);
        validateSupportedTopology(start, end, nodesById, edgeIndex);
        List<WorkflowNode> linearNodes = isLinear(edgeIndex)
                ? orderLinearPath(start, end, nodesById, edgeIndex)
                : List.of();
        return new WorkflowExecutionPlan(start, end, nodesById, edgeIndex.outgoing(), linearNodes);
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

    private void validateSupportedTopology(WorkflowNode start, WorkflowNode end, Map<String, WorkflowNode> nodesById,
            EdgeIndex edgeIndex) {
        validateNodeDegrees(start, end, nodesById, edgeIndex);
        validateReachability(start, end, nodesById, edgeIndex);
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
            if ("condition".equals(type)) {
                validateConditionNodeEdges(node, outgoing);
            }
            else {
                if (outDegree > 1) {
                    throw new BusinessException("WORKFLOW_UNSUPPORTED",
                            "Only condition nodes can branch: " + node.id());
                }
                if (outgoing.stream().anyMatch(WorkflowExecutionEdge::conditional)) {
                    throw new BusinessException("WORKFLOW_UNSUPPORTED",
                            "Only condition node outgoing edges can define conditions: " + node.id());
                }
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
        detectCycles(start.id(), edgeIndex.outgoing(), new HashSet<>(), new HashSet<>());
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
            Set<String> visited) {
        if (visited.contains(nodeId)) {
            return;
        }
        if (!visiting.add(nodeId)) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow cycles are not supported");
        }
        for (WorkflowExecutionEdge edge : outgoing.getOrDefault(nodeId, List.of())) {
            detectCycles(edge.to(), outgoing, visiting, visited);
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
