package com.example.agentdemo.workflow;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class WorkflowDefinitionContractValidator {

    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.-]*)\\s*}}");

    public void validate(WorkflowDefinition definition) {
        validateTemplateVariables(definition);
        validateStructuredOutputReferences(definition);
    }

    private void validateTemplateVariables(WorkflowDefinition definition) {
        Set<String> nodeIds = definition.nodes().stream()
                .map(WorkflowNode::id)
                .collect(Collectors.toSet());
        Set<String> stateKeys = collectStateKeys(definition);
        for (WorkflowNode node : definition.nodes()) {
            validateTemplateValue(node.config(), node.id(), nodeIds, stateKeys);
        }
    }

    private Set<String> collectStateKeys(WorkflowDefinition definition) {
        return definition.nodes().stream()
                .map(WorkflowNode::config)
                .map(config -> config.get("writeState"))
                .filter(Map.class::isInstance)
                .map(writeState -> (Map<?, ?>) writeState)
                .flatMap(writeState -> writeState.keySet().stream())
                .map(String::valueOf)
                .filter(key -> !key.isBlank())
                .collect(Collectors.toSet());
    }

    private void validateTemplateValue(Object value, String ownerNodeId, Set<String> nodeIds,
            Set<String> stateKeys) {
        if (value instanceof String text) {
            validateTemplateString(text, ownerNodeId, nodeIds, stateKeys);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> validateTemplateValue(child, ownerNodeId, nodeIds, stateKeys));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(child -> validateTemplateValue(child, ownerNodeId, nodeIds, stateKeys));
        }
    }

    private void validateTemplateString(String text, String ownerNodeId, Set<String> nodeIds,
            Set<String> stateKeys) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(text);
        while (matcher.find()) {
            validateTemplateVariable(matcher.group(1), ownerNodeId, nodeIds, stateKeys);
        }
    }

    private void validateTemplateVariable(String variable, String ownerNodeId, Set<String> nodeIds,
            Set<String> stateKeys) {
        if (variable.equals("input") || variable.startsWith("input.")
                || variable.equals("context")
                || variable.equals("lastOutput") || variable.startsWith("lastOutput.")
                || variable.equals("toolResult")
                || variable.equals("answer")) {
            return;
        }
        if (variable.startsWith("state.")) {
            String statePath = variable.substring("state.".length());
            String topLevelStateKey = statePath.contains(".")
                    ? statePath.substring(0, statePath.indexOf('.'))
                    : statePath;
            if (stateKeys.contains(statePath) || stateKeys.contains(topLevelStateKey)) {
                return;
            }
            throw new IllegalArgumentException("节点 " + ownerNodeId + " 引用了未写入的状态变量 {{" + variable
                    + "}}，请先在上游节点 config.writeState 中写入 " + topLevelStateKey
                    + "，或改用 {{nodes.<nodeId>.parsed.<field>}}");
        }
        if (variable.startsWith("nodes.")) {
            String[] parts = variable.split("\\.");
            if (!nodeIds.contains(parts[1])) {
                throw new IllegalArgumentException("节点 " + ownerNodeId + " 引用了不存在的节点模板变量 {{" + variable + "}}");
            }
            if (parts.length == 2) {
                return;
            }
            if ("output".equals(parts[2])) {
                throw new IllegalArgumentException("节点 " + ownerNodeId + " 使用了不支持的模板变量 {{" + variable
                        + "}}，LLM 节点回答请使用 {{nodes." + parts[1] + ".answer}}");
            }
            return;
        }
        String prefix = variable.contains(".") ? variable.substring(0, variable.indexOf('.')) : variable;
        if (nodeIds.contains(prefix)) {
            throw new IllegalArgumentException("节点 " + ownerNodeId + " 使用了不支持的模板变量 {{" + variable
                    + "}}，引用节点输出请使用 {{nodes." + prefix + ".answer}}");
        }
        throw new IllegalArgumentException("节点 " + ownerNodeId + " 使用了不支持的模板变量 {{" + variable + "}}");
    }

    private void validateStructuredOutputReferences(WorkflowDefinition definition) {
        Map<String, WorkflowNode> nodesById = definition.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::id, Function.identity(), (first, second) -> first,
                        LinkedHashMap::new));
        for (WorkflowNode node : definition.nodes()) {
            validateStructuredOutputReferences(node.config(), node.id(), nodesById);
        }
    }

    private void validateStructuredOutputReferences(Object value, String ownerNodeId,
            Map<String, WorkflowNode> nodesById) {
        if (value instanceof String text) {
            Matcher matcher = TEMPLATE_PATTERN.matcher(text);
            while (matcher.find()) {
                validateStructuredOutputReference(matcher.group(1), ownerNodeId, nodesById);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> validateStructuredOutputReferences(child, ownerNodeId, nodesById));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(child -> validateStructuredOutputReferences(child, ownerNodeId, nodesById));
        }
    }

    private void validateStructuredOutputReference(String variable, String ownerNodeId,
            Map<String, WorkflowNode> nodesById) {
        if (!variable.startsWith("nodes.")) {
            return;
        }
        String[] parts = variable.split("\\.");
        if (parts.length < 4 || !"parsed".equals(parts[2])) {
            return;
        }
        WorkflowNode sourceNode = nodesById.get(parts[1]);
        if (sourceNode == null) {
            return;
        }
        String parsedPath = String.join(".", Arrays.copyOfRange(parts, 3, parts.length));
        if (!"json".equals(String.valueOf(sourceNode.config().get("outputMode")))
                || !schemaDeclaresParsedPath(sourceNode.config().get("outputSchema"), parsedPath)) {
            throw new IllegalArgumentException("节点 " + ownerNodeId + " 引用了结构化输出字段 {{" + variable
                    + "}}，但上游 LLM 节点 " + sourceNode.id()
                    + " 没有声明 outputMode=json 和 outputSchema.properties." + parsedPath
                    + "。请为该 LLM 节点补齐结构化输出字段、JSON 输出约束和 writeState。");
        }
    }

    private boolean schemaDeclaresParsedPath(Object outputSchema, String parsedPath) {
        if (!(outputSchema instanceof Map<?, ?>)) {
            return false;
        }
        Object current = outputSchema;
        for (String segment : parsedPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap)) {
                return false;
            }
            Object properties = currentMap.get("properties");
            if (!(properties instanceof Map<?, ?> propertyMap)) {
                return false;
            }
            current = propertyMap.get(segment);
            if (current == null) {
                return false;
            }
        }
        return true;
    }
}
