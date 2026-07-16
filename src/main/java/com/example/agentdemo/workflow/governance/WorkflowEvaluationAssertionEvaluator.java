package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.workflow.WorkflowRuntime;
import com.example.agentdemo.workflow.WorkflowEvaluationFixtures;
import com.example.agentdemo.workflow.WorkflowStepSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class WorkflowEvaluationAssertionEvaluator {

    private static final int MAX_OUTPUT_SUMMARY_CHARS = 1_000;
    private static final Pattern JSON_OBJECT_FRAGMENT = Pattern.compile("\\{\\s*\"[^\"]+\"\\s*:");
    private static final Pattern JSON_ARRAY_FRAGMENT = Pattern.compile(
            "\\[\\s*(?:\"|\\{|\\[|-?\\d|true\\b|false\\b|null\\b)", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;

    WorkflowEvaluationAssertionEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<WorkflowEvaluationAssertionResult> evaluate(
            List<WorkflowEvaluationAssertion> assertions,
            WorkflowRuntime.WorkflowExecutionResult execution) {
        EvaluationEvidence evidence = evidence(execution);
        return assertions.stream().map(assertion -> evaluateAssertion(assertion, evidence)).toList();
    }

    String outputSummary(Object output) {
        String text = finalText(output);
        if (StringUtils.hasText(text)) {
            return truncate(text.trim());
        }
        try {
            return truncate(objectMapper.writeValueAsString(output));
        }
        catch (JsonProcessingException exception) {
            return truncate(String.valueOf(output));
        }
    }

    private WorkflowEvaluationAssertionResult evaluateAssertion(WorkflowEvaluationAssertion assertion,
            EvaluationEvidence evidence) {
        List<String> expected = assertion.values();
        List<String> actual;
        boolean passed;
        switch (assertion.type()) {
            case PATH_NODE_TYPES_INCLUDE_IN_ORDER -> {
                actual = evidence.nodeTypes;
                passed = containsInOrder(evidence.nodeTypes, expected);
            }
            case PATH_NODE_TYPES_EXCLUDE -> {
                actual = evidence.nodeTypes;
                passed = expected.stream().noneMatch(value -> containsIgnoreCase(evidence.nodeTypes, value));
            }
            case TOOL_SUCCEEDED -> {
                actual = evidence.succeededTools;
                passed = expected.stream().allMatch(value -> containsIgnoreCase(evidence.succeededTools, value));
            }
            case TOOL_FAILED -> {
                actual = evidence.failedTools;
                passed = expected.stream().allMatch(value -> containsIgnoreCase(evidence.failedTools, value));
            }
            case TOOL_OUTPUT_FIELD_EQUALS -> {
                String toolName = expected.isEmpty() ? "" : expected.getFirst();
                List<JsonNode> matchingValues = evidence.toolCalls.stream()
                        .filter(toolCall -> toolName.equalsIgnoreCase(toolCall.path("toolName").asText()))
                        .map(toolCall -> fieldValue(toolCall.path("output"), assertion.field()))
                        .filter(Objects::nonNull)
                        .toList();
                JsonNode expectedValue = objectMapper.valueToTree(assertion.expectedValue());
                passed = matchingValues.stream().anyMatch(expectedValue::equals);
                actual = matchingValues.stream().map(JsonNode::toString).toList();
                expected = List.of(assertion.field() + "=" + expectedValue);
            }
            case PARSED_FIELDS_INCLUDE -> {
                actual = evidence.parsedFields;
                passed = expected.stream().allMatch(value -> containsIgnoreCase(evidence.parsedFields, value));
            }
            case FINAL_OUTPUT_CUSTOMER_READABLE -> {
                actual = evidence.finalText == null ? List.of() : List.of(evidence.finalText);
                passed = customerReadable(evidence.finalText);
            }
            case FINAL_OUTPUT_CONTAINS_ANY -> {
                actual = evidence.finalText == null ? List.of() : List.of(evidence.finalText);
                passed = evidence.finalText != null
                        && expected.stream().anyMatch(value -> containsIgnoreCase(evidence.finalText, value));
            }
            case FINAL_OUTPUT_CONTAINS_ALL -> {
                actual = evidence.finalText == null ? List.of() : List.of(evidence.finalText);
                passed = evidence.finalText != null
                        && expected.stream().allMatch(value -> containsIgnoreCase(evidence.finalText, value));
            }
            case FINAL_OUTPUT_EXCLUDES -> {
                actual = evidence.finalText == null ? List.of() : List.of(evidence.finalText);
                passed = evidence.finalText != null
                        && expected.stream().noneMatch(value -> containsIgnoreCase(evidence.finalText, value));
            }
            case STATIC_RULE_BLOCK_ABSENT -> {
                actual = List.of("not-applicable-to-runtime");
                passed = false;
            }
            default -> throw new IllegalStateException("Unsupported workflow evaluation assertion: " + assertion.type());
        }
        return new WorkflowEvaluationAssertionResult(
                assertion.id(),
                assertion.type(),
                passed ? WorkflowEvaluationAssertionStatus.PASSED : WorkflowEvaluationAssertionStatus.FAILED,
                expected,
                actual);
    }

    private EvaluationEvidence evidence(WorkflowRuntime.WorkflowExecutionResult execution) {
        List<String> nodeTypes = execution.steps().stream().map(WorkflowStepSummary::nodeType).toList();
        List<JsonNode> toolCalls = new ArrayList<>();
        Set<String> parsedFields = new LinkedHashSet<>();
        for (WorkflowStepSummary step : execution.steps()) {
            JsonNode root = objectMapper.valueToTree(step.output());
            collectParsedFields(root, parsedFields);
            if ("tool".equalsIgnoreCase(step.nodeType())) {
                collectToolEvidence(root, toolCalls);
            }
        }
        List<String> succeededTools = toolCalls.stream()
                .filter(toolCall -> toolCall.path("succeeded").asBoolean(false))
                .map(toolCall -> toolCall.path("toolName").asText())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        List<String> failedTools = toolCalls.stream()
                .filter(toolCall -> !toolCall.path("succeeded").asBoolean(true))
                .map(toolCall -> toolCall.path("toolName").asText())
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return new EvaluationEvidence(
                nodeTypes,
                toolCalls,
                succeededTools,
                failedTools,
                List.copyOf(parsedFields),
                finalText(execution.output()));
    }

    private void collectToolEvidence(JsonNode node, List<JsonNode> toolCalls) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            if (node.has("toolName") && node.has("succeeded")) {
                toolCalls.add(node);
            }
            node.elements().forEachRemaining(child -> collectToolEvidence(child, toolCalls));
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectToolEvidence(child, toolCalls));
        }
    }

    private void collectParsedFields(JsonNode node, Set<String> parsedFields) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            JsonNode parsed = node.get("parsed");
            if (parsed != null && parsed.isObject()) {
                parsed.fieldNames().forEachRemaining(parsedFields::add);
            }
            node.elements().forEachRemaining(child -> collectParsedFields(child, parsedFields));
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectParsedFields(child, parsedFields));
        }
    }

    private String finalText(Object output) {
        JsonNode node = objectMapper.valueToTree(output);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        JsonNode answer = node.get("answer");
        return answer != null && answer.isTextual() ? answer.asText() : null;
    }

    private boolean customerReadable(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String trimmed = text.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return !trimmed.startsWith("{")
                && !trimmed.startsWith("[")
                && !trimmed.contains("{{")
                && !trimmed.contains("}}")
                && !JSON_OBJECT_FRAGMENT.matcher(trimmed).find()
                && !JSON_ARRAY_FRAGMENT.matcher(trimmed).find()
                && !normalized.contains(WorkflowEvaluationFixtures.ERROR_EVALUATION_TOOL_FAILURE
                        .toLowerCase(Locale.ROOT))
                && !normalized.contains("injected workflow evaluation tool failure")
                && !trimmed.contains("\tat ");
    }

    private boolean containsInOrder(List<String> actual, List<String> expected) {
        int expectedIndex = 0;
        for (String value : actual) {
            if (expectedIndex < expected.size() && value.equalsIgnoreCase(expected.get(expectedIndex))) {
                expectedIndex++;
            }
        }
        return expectedIndex == expected.size();
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(expected));
    }

    private boolean containsIgnoreCase(String text, String expected) {
        return text.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    private JsonNode fieldValue(JsonNode root, String field) {
        if (root == null || root.isMissingNode() || !StringUtils.hasText(field)) {
            return null;
        }
        JsonNode current = root;
        for (String segment : field.split("\\.")) {
            current = current.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= MAX_OUTPUT_SUMMARY_CHARS) {
            return value;
        }
        return value.substring(0, MAX_OUTPUT_SUMMARY_CHARS);
    }

    private record EvaluationEvidence(
            List<String> nodeTypes,
            List<JsonNode> toolCalls,
            List<String> succeededTools,
            List<String> failedTools,
            List<String> parsedFields,
            String finalText) {
    }
}
