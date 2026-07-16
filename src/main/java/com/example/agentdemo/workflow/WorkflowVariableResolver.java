package com.example.agentdemo.workflow;

import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    public Object renderDeep(Object value, WorkflowExecutionState state) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            map.forEach((key, child) -> rendered.put(String.valueOf(key), renderDeep(child, state)));
            return rendered;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> rendered = new ArrayList<>();
            iterable.forEach(child -> rendered.add(renderDeep(child, state)));
            return rendered;
        }
        if (value != null && value.getClass().isArray()) {
            List<Object> rendered = new ArrayList<>();
            for (int index = 0; index < Array.getLength(value); index++) {
                rendered.add(renderDeep(Array.get(value, index), state));
            }
            return rendered;
        }
        return renderValue(value, state);
    }

    WorkflowResolvedValue resolveReference(String template, WorkflowExecutionState state) {
        if (template == null) {
            return WorkflowResolvedValue.missing();
        }
        Matcher matcher = EXACT_TEMPLATE_PATTERN.matcher(template);
        if (!matcher.matches()) {
            return WorkflowResolvedValue.missing();
        }
        return resolveWithPresence(matcher.group(1), state);
    }

    Object resolve(String name, WorkflowExecutionState state) {
        return switch (name) {
            case "input" -> state.primaryInput();
            case "context" -> state.contextText();
            case "lastOutput" -> state.lastOutput();
            case "state" -> state.stateVariables();
            case "toolResult" -> state.lastToolResult();
            case "answer" -> state.answer();
            default -> resolveDotted(name, state);
        };
    }

    private WorkflowResolvedValue resolveWithPresence(String name, WorkflowExecutionState state) {
        return switch (name) {
            case "input" -> WorkflowResolvedValue.present(state.primaryInput());
            case "context" -> WorkflowResolvedValue.present(state.contextText());
            case "lastOutput" -> state.lastOutput() == null
                    ? WorkflowResolvedValue.missing()
                    : WorkflowResolvedValue.present(state.lastOutput());
            case "state" -> WorkflowResolvedValue.present(state.stateVariables());
            case "toolResult" -> state.toolCalls().isEmpty()
                    ? WorkflowResolvedValue.missing()
                    : WorkflowResolvedValue.present(state.lastToolResult());
            case "answer" -> state.answer() == null
                    ? WorkflowResolvedValue.missing()
                    : WorkflowResolvedValue.present(state.answer());
            default -> resolveDottedWithPresence(name, state);
        };
    }

    private WorkflowResolvedValue resolveDottedWithPresence(String name, WorkflowExecutionState state) {
        if (name.startsWith("input.")) {
            return resolvePathWithPresence(state.input(), name.substring("input.".length()));
        }
        if (name.startsWith("lastOutput.")) {
            return state.lastOutput() == null
                    ? WorkflowResolvedValue.missing()
                    : resolvePathWithPresence(state.lastOutput(), name.substring("lastOutput.".length()));
        }
        if (name.startsWith("state.")) {
            String path = name.substring("state.".length());
            String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
            if (!state.hasStateVariable(root)) {
                return WorkflowResolvedValue.missing();
            }
            return resolvePathWithPresence(state.stateVariables(), path);
        }
        if (name.startsWith("nodes.")) {
            String path = name.substring("nodes.".length());
            String[] parts = path.split("\\.");
            if (parts.length == 0 || !state.hasNodeOutput(parts[0])) {
                return WorkflowResolvedValue.missing();
            }
            Object output = state.nodeOutput(parts[0]);
            if (parts.length == 1) {
                return WorkflowResolvedValue.present(output);
            }
            return resolvePathWithPresence(output,
                    String.join(".", List.of(parts).subList(1, parts.length)));
        }
        return WorkflowResolvedValue.missing();
    }

    private WorkflowResolvedValue resolvePathWithPresence(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(part)) {
                    return WorkflowResolvedValue.missing();
                }
                current = map.get(part);
                continue;
            }
            if (current instanceof List<?> list && part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                if (index >= list.size()) {
                    return WorkflowResolvedValue.missing();
                }
                current = list.get(index);
                continue;
            }
            if (current != null && current.getClass().isArray() && part.matches("\\d+")) {
                int index = Integer.parseInt(part);
                if (index >= Array.getLength(current)) {
                    return WorkflowResolvedValue.missing();
                }
                current = Array.get(current, index);
                continue;
            }
            return WorkflowResolvedValue.missing();
        }
        return WorkflowResolvedValue.present(current);
    }

    private Object resolveDotted(String name, WorkflowExecutionState state) {
        if (name.startsWith("input.")) {
            return resolvePath(state.input(), name.substring("input.".length()));
        }
        if (name.startsWith("lastOutput.")) {
            return resolvePath(state.lastOutput(), name.substring("lastOutput.".length()));
        }
        if (name.startsWith("state.")) {
            return resolvePath(state.stateVariables(), name.substring("state.".length()));
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
