package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.SecretRedactor;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class WorkflowCompiler {

    private static final Set<String> SUPPORTED_TYPES = Set.of("start", "retriever", "tavily_search", "llm", "tool",
            "http_request", "report_export", "custom", "condition", "parallel", "join", "variable_aggregator",
            "end", "loop", "loop_back", "subgraph", "dynamic");
    private static final Set<String> REPORT_FORMATS = Set.of("pdf", "docx", "html", "markdown", "txt");
    private static final Set<String> SUPPORTED_EDGE_CONDITIONS = Set.of("true", "false", "body", "exit");
    private static final Pattern NODE_VARIABLE_REFERENCE = Pattern.compile(
            "^\\s*\\{\\{\\s*nodes\\.([a-zA-Z0-9_-]+)(?:\\.[a-zA-Z0-9_.-]+)?\\s*}}\\s*$");
    private static final Pattern REPORT_CONTENT_REFERENCE = Pattern.compile(
            "^\\s*\\{\\{\\s*nodes\\.([a-zA-Z0-9_-]+)\\.([a-zA-Z0-9_.-]+)\\s*}}\\s*$");
    private static final Pattern AGGREGATION_GROUP_KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{0,63}");
    private static final Pattern CUSTOM_INPUT_KEY = Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{0,63}");
    private static final Pattern EMBEDDED_NODE_VARIABLE_REFERENCE = Pattern.compile(
            "\\{\\{\\s*nodes\\.([a-zA-Z0-9_-]+)(?:\\.[a-zA-Z0-9_.-]+)?\\s*}}");
    private static final Set<String> AGGREGATION_OUTPUT_TYPES = Set.of(
            "string", "number", "boolean", "object", "array");

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
        validateCustomNodes(nodesById, edgeIndex);
        validateReportExports(nodesById, edgeIndex);
        validateVariableAggregators(nodesById, edgeIndex);
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
        if ("http_request".equals(normalizedType)) {
            validateHttpRequestConfig(node);
        }
        if ("report_export".equals(normalizedType)) {
            validateReportExportConfig(node);
        }
        if ("custom".equals(normalizedType)) {
            validateCustomConfig(node);
        }
    }

    private void validateCustomConfig(WorkflowNode node) {
        String mode = String.valueOf(node.config().getOrDefault("mode", "ai")).toLowerCase(Locale.ROOT);
        if (!(node.config().getOrDefault("inputs", Map.of()) instanceof Map<?, ?> inputs)) {
            throw validation(node, "inputs must be object");
        }
        for (Object configuredKey : inputs.keySet()) {
            String key = String.valueOf(configuredKey);
            if (!CUSTOM_INPUT_KEY.matcher(key).matches()) {
                throw validation(node,
                        "input names must start with a letter and contain only letters, digits, _ or -: " + key);
            }
        }
        if ("ai".equals(mode)) {
            if (!StringUtils.hasText(String.valueOf(node.config().getOrDefault("instruction", "")))) {
                throw validation(node, "instruction is required in ai mode");
            }
            return;
        }
        if ("template".equals(mode)) {
            if (!node.config().containsKey("template") || node.config().get("template") == null) {
                throw validation(node, "template is required in template mode");
            }
            return;
        }
        throw validation(node, "mode must be ai or template");
    }

    private void validateCustomNodes(Map<String, WorkflowNode> nodesById, EdgeIndex edgeIndex) {
        nodesById.values().stream()
                .filter(node -> "custom".equals(normalizeType(node)))
                .forEach(node -> {
                    Set<String> ancestors = collectAncestors(node.id(), edgeIndex.incoming());
                    validateCustomReferences(node, node.config().getOrDefault("inputs", Map.of()), ancestors);
                    if ("template".equalsIgnoreCase(String.valueOf(node.config().getOrDefault("mode", "ai")))) {
                        validateCustomReferences(node, node.config().get("template"), ancestors);
                    }
                });
    }

    private void validateCustomReferences(WorkflowNode node, Object configured, Set<String> ancestors) {
        if (configured instanceof Map<?, ?> map) {
            map.values().forEach(value -> validateCustomReferences(node, value, ancestors));
            return;
        }
        if (configured instanceof Iterable<?> iterable) {
            iterable.forEach(value -> validateCustomReferences(node, value, ancestors));
            return;
        }
        if (!(configured instanceof String text)) {
            return;
        }
        Matcher matcher = EMBEDDED_NODE_VARIABLE_REFERENCE.matcher(text);
        while (matcher.find()) {
            if (!ancestors.contains(matcher.group(1))) {
                throw validation(node, "must reference an upstream node: " + matcher.group(1));
            }
        }
    }

    private void validateReportExportConfig(WorkflowNode node) {
        Object configured = node.config().getOrDefault("formats", List.of("pdf"));
        if (!(configured instanceof Iterable<?> values)) {
            throw validation(node, "formats must be array");
        }
        Set<String> formats = new HashSet<>();
        for (Object value : values) {
            String format = String.valueOf(value).toLowerCase(Locale.ROOT);
            if (!REPORT_FORMATS.contains(format)) {
                throw validation(node, "formats contains unsupported format: " + value);
            }
            if (!formats.add(format)) {
                throw validation(node, "formats contains duplicate format: " + format);
            }
        }
        if (formats.isEmpty()) {
            throw validation(node, "formats must contain at least one format");
        }
    }

    private void validateReportExports(Map<String, WorkflowNode> nodesById, EdgeIndex edgeIndex) {
        nodesById.values().stream()
                .filter(node -> "report_export".equals(normalizeType(node)))
                .forEach(node -> validateReportContent(node, nodesById, edgeIndex));
    }

    private void validateReportContent(WorkflowNode node, Map<String, WorkflowNode> nodesById,
            EdgeIndex edgeIndex) {
        String content = String.valueOf(node.config().getOrDefault("content", ""));
        Matcher matcher = REPORT_CONTENT_REFERENCE.matcher(content);
        if (!matcher.matches()) {
            throw validation(node, "content must be an exact reachable upstream string variable");
        }
        String sourceId = matcher.group(1);
        String path = matcher.group(2);
        Set<String> ancestors = collectAncestors(node.id(), edgeIndex.incoming());
        WorkflowNode source = nodesById.get(sourceId);
        if (!ancestors.contains(sourceId) || source == null || !isStringOutput(source, path)) {
            throw validation(node, "content must be an exact reachable upstream string variable");
        }
    }

    private boolean isStringOutput(WorkflowNode node, String path) {
        return switch (normalizeType(node)) {
            case "start" -> "message".equals(path);
            case "retriever" -> Set.of("query", "retrievedContext").contains(path);
            case "tavily_search" -> Set.of("query", "answer").contains(path);
            case "llm" -> "answer".equals(path) || declaredStructuredString(node, path);
            case "custom" -> customStringOutput(node, path);
            case "http_request" -> "body".equals(path);
            case "variable_aggregator" -> aggregatorStringOutput(node, path);
            default -> false;
        };
    }

    private boolean customStringOutput(WorkflowNode node, String path) {
        String mode = String.valueOf(node.config().getOrDefault("mode", "ai")).toLowerCase(Locale.ROOT);
        if ("ai".equals(mode)) {
            return "answer".equals(path)
                    || ("output".equals(path)
                            && "text".equalsIgnoreCase(String.valueOf(
                                    node.config().getOrDefault("outputMode", "text"))))
                    || declaredStructuredString(node, path);
        }
        return "output".equals(path) && node.config().get("template") instanceof String;
    }

    private boolean declaredStructuredString(WorkflowNode node, String path) {
        if (!path.startsWith("parsed.")) {
            return false;
        }
        Object current = node.config().get("outputSchema");
        for (String segment : path.substring("parsed.".length()).split("\\.")) {
            if (!(current instanceof Map<?, ?> schema)
                    || !(schema.get("properties") instanceof Map<?, ?> properties)) {
                return false;
            }
            current = properties.get(segment);
        }
        return current instanceof Map<?, ?> field && "string".equalsIgnoreCase(String.valueOf(field.get("type")));
    }

    private boolean aggregatorStringOutput(WorkflowNode node, String path) {
        String mode = String.valueOf(node.config().getOrDefault("mode", "single"));
        if (!"groups".equalsIgnoreCase(mode)) {
            return "output".equals(path)
                    && "string".equalsIgnoreCase(String.valueOf(node.config().getOrDefault("outputType", "string")));
        }
        if (!path.endsWith(".output") || !(node.config().get("groups") instanceof Iterable<?> groups)) {
            return false;
        }
        String key = path.substring(0, path.length() - ".output".length());
        for (Object candidate : groups) {
            if (candidate instanceof Map<?, ?> group
                    && key.equals(String.valueOf(group.get("key")))
                    && "string".equalsIgnoreCase(String.valueOf(
                            group.containsKey("outputType") ? group.get("outputType") : "string"))) {
                return true;
            }
        }
        return false;
    }

    private void validateHttpRequestConfig(WorkflowNode node) {
        String method = String.valueOf(node.config().getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
        Map<?, ?> authorization = node.config().get("authorization") instanceof Map<?, ?> map
                ? map : Map.of("type", "none");
        String authorizationType = String.valueOf(authorization.containsKey("type") ? authorization.get("type") : "none")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("none", "credential").contains(authorizationType)) {
            throw validation(node, "authorization.type must be none or credential");
        }
        if ("credential".equals(authorizationType)
                && !StringUtils.hasText(String.valueOf(authorization.get("credentialId")))) {
            throw validation(node, "authorization.credentialId is required");
        }
        if (authorization.keySet().stream().map(String::valueOf)
                .anyMatch(key -> !Set.of("type", "credentialId").contains(key))) {
            throw validation(node, "authorization may only reference credentialId; inline secrets are forbidden");
        }

        validateHttpRows(node, "headers", node.config().get("headers"), true);
        validateHttpRows(node, "params", node.config().get("params"), false);

        Map<?, ?> body = node.config().get("body") instanceof Map<?, ?> map
                ? map : Map.of("type", "none");
        String bodyType = String.valueOf(body.containsKey("type") ? body.get("type") : "none")
                .toLowerCase(Locale.ROOT);
        if (!Set.of("none", "json", "raw", "x-www-form-urlencoded", "form-data").contains(bodyType)) {
            throw validation(node, "body.type is unsupported");
        }
        if (("GET".equals(method) || "HEAD".equals(method)) && !"none".equals(bodyType)) {
            throw validation(node, method + " requests cannot contain a body");
        }
        if (("x-www-form-urlencoded".equals(bodyType) || "form-data".equals(bodyType))) {
            validateHttpRows(node, "body.value", body.get("value"), false);
        }
        if ("json".equals(bodyType) && containsSensitiveHttpBodyField(body.get("value"))) {
            throw new BusinessException("WORKFLOW_HTTP_INLINE_SECRET_BLOCKED",
                    "HTTP body contains a sensitive field; use a managed credential");
        }

        int retryCount = configInteger(node.config().get("retryCount"), 0);
        if (retryCount > 0 && !Set.of("GET", "HEAD").contains(method)
                && !Boolean.TRUE.equals(node.config().get("idempotent"))) {
            throw new BusinessException("WORKFLOW_RETRY_NOT_ALLOWED",
                    "HTTP request retry requires config.idempotent=true for method " + method + ": " + node.id());
        }
    }

    private void validateHttpRows(WorkflowNode node, String field, Object configured, boolean headers) {
        if (configured == null) {
            return;
        }
        if (!(configured instanceof Iterable<?> rows)) {
            throw validation(node, field + " must be array");
        }
        for (Object rawRow : rows) {
            if (!(rawRow instanceof Map<?, ?> row)) {
                throw validation(node, field + " items must be object");
            }
            Object keyValue = row.get("key");
            if (keyValue == null || !StringUtils.hasText(String.valueOf(keyValue))) {
                continue;
            }
            String key = String.valueOf(keyValue);
            if (SecretRedactor.isSensitiveKey(key)) {
                throw new BusinessException("WORKFLOW_HTTP_INLINE_SECRET_BLOCKED",
                        "HTTP " + field + " contains sensitive key " + key + "; use a managed credential");
            }
            if (headers && Set.of("host", "content-length", "connection", "transfer-encoding",
                    "proxy-authorization").contains(key.toLowerCase(Locale.ROOT))) {
                throw validation(node, "restricted HTTP header: " + key);
            }
        }
    }

    private boolean containsSensitiveHttpBodyField(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (SecretRedactor.isSensitiveKey(String.valueOf(entry.getKey()))
                        || containsSensitiveHttpBodyField(entry.getValue())) {
                    return true;
                }
            }
        }
        else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsSensitiveHttpBodyField(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int configInteger(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
        }
        catch (NumberFormatException ex) {
            return defaultValue;
        }
    }

    private BusinessException validation(WorkflowNode node, String detail) {
        return new BusinessException("WORKFLOW_VALIDATION_FAILED", "Config " + node.id() + " " + detail);
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

    private void validateVariableAggregators(Map<String, WorkflowNode> nodesById, EdgeIndex edgeIndex) {
        nodesById.values().stream()
                .filter(node -> "variable_aggregator".equals(normalizeType(node)))
                .forEach(node -> validateVariableAggregator(node, edgeIndex));
    }

    private void validateVariableAggregator(WorkflowNode node, EdgeIndex edgeIndex) {
        String mode = String.valueOf(node.config().getOrDefault("mode", "single")).toLowerCase(Locale.ROOT);
        Set<String> ancestors = collectAncestors(node.id(), edgeIndex.incoming());
        if ("single".equals(mode)) {
            validateAggregationOutputType(node.id(), "output",
                    String.valueOf(node.config().getOrDefault("outputType", "string")));
            validateAggregationVariables(node.id(), "output", node.config().get("variables"), ancestors);
            return;
        }
        if (!"groups".equals(mode)) {
            return;
        }
        Object configuredGroups = node.config().get("groups");
        if (!(configuredGroups instanceof Iterable<?> groups)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + ".groups must be array");
        }
        Set<String> keys = new HashSet<>();
        int count = 0;
        for (Object configuredGroup : groups) {
            count++;
            if (!(configuredGroup instanceof Map<?, ?> group)) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Config " + node.id() + ".groups items must be object");
            }
            String key = group.get("key") == null ? "" : String.valueOf(group.get("key")).trim();
            if (!AGGREGATION_GROUP_KEY.matcher(key).matches()) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Variable aggregator " + node.id() + " group key must start with a letter and contain only letters, digits, _ or -");
            }
            if (!keys.add(key)) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Variable aggregator " + node.id() + " has duplicate group key: " + key);
            }
            String outputType = group.get("outputType") == null ? "string" : String.valueOf(group.get("outputType"));
            validateAggregationOutputType(node.id(), key, outputType);
            validateAggregationVariables(node.id(), key, group.get("variables"), ancestors);
        }
        if (count == 0) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Variable aggregator " + node.id() + " groups must not be empty");
        }
    }

    private void validateAggregationOutputType(String nodeId, String groupKey, String outputType) {
        if (!AGGREGATION_OUTPUT_TYPES.contains(outputType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Variable aggregator " + nodeId + " group " + groupKey + " has unsupported outputType: " + outputType);
        }
    }

    private void validateAggregationVariables(String nodeId, String groupKey, Object configuredVariables,
            Set<String> ancestors) {
        if (!(configuredVariables instanceof Iterable<?> variables)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Variable aggregator " + nodeId + " group " + groupKey + " variables must be array");
        }
        int count = 0;
        for (Object configuredVariable : variables) {
            count++;
            Matcher matcher = NODE_VARIABLE_REFERENCE.matcher(String.valueOf(configuredVariable));
            if (!matcher.matches()) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Variable aggregator " + nodeId + " group " + groupKey
                                + " variables must be exact {{nodes.<nodeId>.<field>}} references");
            }
            if (!ancestors.contains(matcher.group(1))) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Variable aggregator " + nodeId + " group " + groupKey
                                + " must reference an upstream node: " + matcher.group(1));
            }
        }
        if (count == 0) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Variable aggregator " + nodeId + " group " + groupKey + " variables must not be empty");
        }
    }

    private Set<String> collectAncestors(String nodeId, Map<String, List<String>> incoming) {
        Set<String> ancestors = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(incoming.getOrDefault(nodeId, List.of()));
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!ancestors.add(current)) {
                continue;
            }
            pending.addAll(incoming.getOrDefault(current, List.of()));
        }
        return ancestors;
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
