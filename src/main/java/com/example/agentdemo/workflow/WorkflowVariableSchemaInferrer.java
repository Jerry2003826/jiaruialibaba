package com.example.agentdemo.workflow;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds a runnable input contract from the input variables actually referenced by a workflow. */
public final class WorkflowVariableSchemaInferrer {

    private static final Pattern INPUT_TEMPLATE =
            Pattern.compile("\\{\\{\\s*input(?:\\.([a-zA-Z][a-zA-Z0-9_.-]*))?\\s*}}");
    private static final Set<String> NUMBER_INPUTS = Set.of(
            "amount", "count", "maxResults", "topK", "limit", "page", "pageSize");
    private static final Set<String> BOOLEAN_INPUTS = Set.of(
            "receiptProvided", "paid", "enabled", "includeAnswer", "includeRawContent");
    private static final Set<String> ARRAY_INPUTS = Set.of(
            "tools", "history", "orderIds", "items", "includeDomains", "excludeDomains");

    private WorkflowVariableSchemaInferrer() {
    }

    public static WorkflowVariableSchema infer(WorkflowDefinition definition, WorkflowVariableSchema declared) {
        Map<String, WorkflowVariableSchema.InputVariable> inputs = new LinkedHashMap<>();
        if (declared != null) {
            declared.inputs().stream()
                    .filter(variable -> variable != null && variable.name() != null && !variable.name().isBlank())
                    .forEach(variable -> inputs.putIfAbsent(variable.name(), variable));
        }

        Map<String, InputUsage> discovered = new LinkedHashMap<>();
        if (definition != null) {
            definition.nodes().forEach(node -> collectInputReferences(
                    node.config(), "tavily_search".equalsIgnoreCase(node.type()), false, discovered));
        }
        discovered.forEach((name, usage) -> inputs.computeIfAbsent(name, ignored -> inferredVariable(name, usage)));

        List<WorkflowVariableSchema.OutputVariable> outputs = declared == null
                ? List.of()
                : declared.outputs();
        return new WorkflowVariableSchema(new ArrayList<>(inputs.values()), outputs);
    }

    private static void collectInputReferences(Object value, boolean tavilyNode, boolean searchQuery,
            Map<String, InputUsage> discovered) {
        if (value instanceof String text) {
            Matcher matcher = INPUT_TEMPLATE.matcher(text);
            while (matcher.find()) {
                String path = matcher.group(1);
                String name = path == null || path.isBlank() ? "message" : topLevel(path);
                boolean nested = path != null && path.contains(".");
                discovered.merge(name, new InputUsage(nested, tavilyNode && searchQuery), InputUsage::merge);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> collectInputReferences(
                    child, tavilyNode, searchQuery || "query".equals(String.valueOf(key)), discovered));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(child -> collectInputReferences(child, tavilyNode, searchQuery, discovered));
            return;
        }
        if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                collectInputReferences(Array.get(value, index), tavilyNode, searchQuery, discovered);
            }
        }
    }

    private static WorkflowVariableSchema.InputVariable inferredVariable(String name, InputUsage usage) {
        String type = usage.nested()
                ? "object"
                : NUMBER_INPUTS.contains(name)
                        ? "number"
                        : BOOLEAN_INPUTS.contains(name)
                                ? "boolean"
                                : ARRAY_INPUTS.contains(name) ? "array" : "string";
        return new WorkflowVariableSchema.InputVariable(name, type, true, null, description(name, usage));
    }

    private static String description(String name, InputUsage usage) {
        if (usage.searchQuery() || "topic".equals(name) || "query".equals(name)) {
            return "输入要研究或搜索的主题";
        }
        return switch (name) {
            case "message" -> "输入给工作流的内容";
            case "count", "maxResults", "limit" -> "结果数量";
            case "tools" -> "可用工具列表";
            default -> "工作流输入：" + name;
        };
    }

    private static String topLevel(String path) {
        int separator = path.indexOf('.');
        return separator < 0 ? path : path.substring(0, separator);
    }

    private record InputUsage(boolean nested, boolean searchQuery) {
        private InputUsage merge(InputUsage other) {
            return new InputUsage(nested || other.nested, searchQuery || other.searchQuery);
        }
    }
}
