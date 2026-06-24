package com.example.agentdemo.workflow;

import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WorkflowVariableResolver {

    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.-]*)\\s*}}");
    private static final Pattern EXACT_TEMPLATE_PATTERN =
            Pattern.compile("^\\s*\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.-]*)\\s*}}\\s*$");

    public String renderString(String template, WorkflowExecutionState state) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(stringValue(resolve(matcher.group(1), state))));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    public Object renderValue(Object value, WorkflowExecutionState state) {
        if (!(value instanceof String text)) {
            return value;
        }
        Matcher exactMatcher = EXACT_TEMPLATE_PATTERN.matcher(text);
        if (exactMatcher.matches()) {
            Object resolved = resolve(exactMatcher.group(1), state);
            return resolved == null ? "" : resolved;
        }
        return renderString(text, state);
    }

    Object resolve(String name, WorkflowExecutionState state) {
        return switch (name) {
            case "input" -> state.primaryInput();
            case "context" -> state.contextText();
            case "lastOutput" -> state.lastOutput();
            case "toolResult" -> state.lastToolResult();
            case "answer" -> state.answer();
            default -> resolveDotted(name, state);
        };
    }

    private Object resolveDotted(String name, WorkflowExecutionState state) {
        if (name.startsWith("input.")) {
            return resolvePath(state.input(), name.substring("input.".length()));
        }
        if (name.startsWith("lastOutput.")) {
            return resolvePath(state.lastOutput(), name.substring("lastOutput.".length()));
        }
        if (name.startsWith("nodes.")) {
            return resolveNodeOutput(name.substring("nodes.".length()), state);
        }
        return null;
    }

    private Object resolveNodeOutput(String path, WorkflowExecutionState state) {
        String[] parts = path.split("\\.");
        if (parts.length == 0) {
            return null;
        }
        Object nodeOutput = state.nodeOutput(parts[0]);
        if (parts.length == 1) {
            return nodeOutput;
        }
        return resolvePath(nodeOutput, String.join(".", List.of(parts).subList(1, parts.length)));
    }

    private Object resolvePath(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            current = resolvePathPart(current, part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private Object resolvePathPart(Object current, String part) {
        if (current instanceof Map<?, ?> map) {
            return map.get(part);
        }
        if (current instanceof List<?> list && part.matches("\\d+")) {
            int index = Integer.parseInt(part);
            return index < list.size() ? list.get(index) : null;
        }
        if (current != null && current.getClass().isArray() && part.matches("\\d+")) {
            int index = Integer.parseInt(part);
            return index < Array.getLength(current) ? Array.get(current, index) : null;
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
