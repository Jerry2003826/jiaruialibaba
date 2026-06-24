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
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class WorkflowCompiler {

    private static final Set<String> SUPPORTED_TYPES = Set.of("start", "retriever", "llm", "tool", "end");

    private final WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry;

    public WorkflowCompiler(WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry) {
        this.workflowNodeSchemaRegistry = workflowNodeSchemaRegistry;
    }

    public List<WorkflowNode> compile(WorkflowDefinition definition) {
        Map<String, WorkflowNode> nodesById = indexNodes(definition.nodes());
        WorkflowNode start = singleNodeByType(nodesById, "start");
        WorkflowNode end = singleNodeByType(nodesById, "end");
        EdgeIndex edgeIndex = indexEdges(definition.edges(), nodesById);
        rejectComplexTopology(start, end, nodesById, edgeIndex);
        return orderLinearPath(start, end, nodesById, edgeIndex);
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
        Map<String, List<String>> outgoing = new HashMap<>();
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
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
            incoming.computeIfAbsent(edge.to(), ignored -> new ArrayList<>()).add(edge.from());
        }
        return new EdgeIndex(outgoing, incoming);
    }

    private void rejectComplexTopology(WorkflowNode start, WorkflowNode end, Map<String, WorkflowNode> nodesById,
            EdgeIndex edgeIndex) {
        for (WorkflowNode node : nodesById.values()) {
            int outDegree = edgeIndex.outgoing().getOrDefault(node.id(), List.of()).size();
            int inDegree = edgeIndex.incoming().getOrDefault(node.id(), List.of()).size();
            if (outDegree > 1 || inDegree > 1) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Only linear DAG workflow is supported in this demo");
            }
        }
        if (!edgeIndex.incoming().getOrDefault(start.id(), List.of()).isEmpty()) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED", "Start node cannot have incoming edges");
        }
        if (!edgeIndex.outgoing().getOrDefault(end.id(), List.of()).isEmpty()) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED", "End node cannot have outgoing edges");
        }
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
            List<String> nextIds = edgeIndex.outgoing().getOrDefault(current.id(), List.of());
            if (nextIds.size() != 1) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Workflow must be a single path from start to end");
            }
            current = nodesById.get(nextIds.getFirst());
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

    private record EdgeIndex(Map<String, List<String>> outgoing, Map<String, List<String>> incoming) {
    }

}
