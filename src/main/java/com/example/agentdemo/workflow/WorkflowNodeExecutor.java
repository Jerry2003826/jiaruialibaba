package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WorkflowNodeExecutor {

    private static final String WORKFLOW_SYSTEM_PROMPT = """
            You are a workflow LLM node. Use the workflow input and retrieved context when available.
            If context is missing, say what is missing instead of inventing details.
            """;
    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.]*)\\s*}}");

    private final RagService ragService;
    private final AiModelService aiModelService;
    private final ToolGatewayService toolGatewayService;

    public WorkflowNodeExecutor(RagService ragService, AiModelService aiModelService,
            ToolGatewayService toolGatewayService) {
        this.ragService = ragService;
        this.aiModelService = aiModelService;
        this.toolGatewayService = toolGatewayService;
    }

    Object execute(String runId, WorkflowNode node, WorkflowExecutionState state) {
        return switch (normalizeType(node)) {
            case "start" -> executeStart(state);
            case "retriever" -> executeRetriever(runId, node, state);
            case "llm" -> executeLlm(node, state);
            case "tool" -> executeTool(node, state);
            case "condition" -> executeCondition(node, state);
            case "end" -> executeEnd(state);
            default -> throw new BusinessException("WORKFLOW_UNSUPPORTED", "Unsupported node type: " + node.type());
        };
    }

    Map<String, Object> nodeInput(WorkflowNode node, WorkflowExecutionState state) {
        Map<String, Object> input = orderedMap();
        input.put("nodeId", node.id());
        input.put("nodeType", normalizeType(node));
        input.put("config", node.config());
        input.put("workflowInput", state.input());
        input.put("lastOutput", state.lastOutput());
        return input;
    }

    String normalizeType(WorkflowNode node) {
        return node.type().toLowerCase(Locale.ROOT);
    }

    Map<String, Object> errorOutput(RuntimeException ex) {
        Map<String, Object> output = orderedMap();
        output.put("error", ex.getMessage());
        return output;
    }

    private Object executeStart(WorkflowExecutionState state) {
        state.setLastOutput(state.input());
        return state.input();
    }

    private Object executeRetriever(String runId, WorkflowNode node, WorkflowExecutionState state) {
        String query = configString(node, "query", state.primaryInput());
        int topK = configInt(node, "topK", 3);
        List<RetrievedContext> contexts = ragService.retrieve(query, topK, runId);
        state.setRetrievedContext(contexts);
        Map<String, Object> output = orderedMap();
        output.put("query", query);
        output.put("topK", topK);
        output.put("retrievedContext", contexts);
        state.setLastOutput(output);
        return output;
    }

    private Object executeLlm(WorkflowNode node, WorkflowExecutionState state) {
        String promptTemplate = configString(node, "prompt",
                "Answer the workflow input using this context: {{context}}\nInput: {{input}}");
        String prompt = renderTemplate(promptTemplate, state);
        AiModelResult result = aiModelService.generate(WORKFLOW_SYSTEM_PROMPT, prompt);
        String answer = result.fallback() ? fallbackAnswer(prompt, state) : result.answer();
        state.setAnswer(answer);

        Map<String, Object> output = orderedMap();
        output.put("prompt", prompt);
        output.put("answer", answer);
        output.put("fallback", result.fallback());
        output.put("errorMessage", result.errorMessage());
        state.setLastOutput(output);
        return output;
    }

    private Object executeTool(WorkflowNode node, WorkflowExecutionState state) {
        String toolName = configString(node, "toolName", "getCurrentTime");
        Map<String, Object> arguments = toolArguments(node, state);
        ToolExecutionLog log = toolGatewayService.execute(toolName, arguments);
        state.addToolCall(log);
        if (!log.succeeded()) {
            throw new WorkflowNodeExecutionException(log.errorCategory(), log.errorMessage(), log);
        }
        state.setLastOutput(log.output());
        return log;
    }

    private Object executeCondition(WorkflowNode node, WorkflowExecutionState state) {
        String left = renderTemplate(configString(node, "left", "{{input}}"), state);
        String operator = configString(node, "operator", "contains").toLowerCase(Locale.ROOT);
        Object right = renderArgument(node.config().getOrDefault("right", ""), state);
        boolean caseSensitive = configBoolean(node, "caseSensitive", false);
        boolean result = evaluateCondition(left, operator, right, caseSensitive);
        state.setLastConditionResult(result);

        Map<String, Object> output = orderedMap();
        output.put("left", left);
        output.put("operator", operator);
        output.put("right", right);
        output.put("caseSensitive", caseSensitive);
        output.put("result", result);
        state.setLastOutput(output);
        return output;
    }

    private boolean evaluateCondition(String left, String operator, Object right, boolean caseSensitive) {
        String leftText = left == null ? "" : left;
        String rightText = right == null ? "" : String.valueOf(right);
        String comparableLeft = caseSensitive ? leftText : leftText.toLowerCase(Locale.ROOT);
        String comparableRight = caseSensitive ? rightText : rightText.toLowerCase(Locale.ROOT);
        return switch (operator) {
            case "equals" -> comparableLeft.equals(comparableRight);
            case "notequals" -> !comparableLeft.equals(comparableRight);
            case "contains" -> comparableLeft.contains(comparableRight);
            case "notcontains" -> !comparableLeft.contains(comparableRight);
            case "startswith" -> comparableLeft.startsWith(comparableRight);
            case "endswith" -> comparableLeft.endsWith(comparableRight);
            case "exists" -> StringUtils.hasText(leftText);
            case "notexists" -> !StringUtils.hasText(leftText);
            default -> throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Unsupported condition operator: " + operator);
        };
    }

    private Map<String, Object> toolArguments(WorkflowNode node, WorkflowExecutionState state) {
        Map<String, Object> arguments = orderedMap();
        Object configuredArguments = node.config().get("arguments");
        if (configuredArguments instanceof Map<?, ?> argumentMap) {
            for (Map.Entry<?, ?> entry : argumentMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    arguments.put(key, renderArgument(entry.getValue(), state));
                }
            }
        }
        if ("calculate".equals(configString(node, "toolName", "getCurrentTime"))
                && !arguments.containsKey("expression")) {
            arguments.put("expression", renderTemplate(configString(node, "expression", ""), state));
        }
        return arguments;
    }

    private Object renderArgument(Object value, WorkflowExecutionState state) {
        if (value instanceof String text) {
            return renderTemplate(text, state);
        }
        return value;
    }

    private Object executeEnd(WorkflowExecutionState state) {
        Object output = state.answer() != null ? finalAnswerOutput(state) : state.lastOutput();
        state.setFinalOutput(output);
        return output;
    }

    private Map<String, Object> finalAnswerOutput(WorkflowExecutionState state) {
        Map<String, Object> output = orderedMap();
        output.put("answer", state.answer());
        output.put("retrievedContext", state.retrievedContext());
        output.put("toolCalls", state.toolCalls());
        return output;
    }

    private String fallbackAnswer(String prompt, WorkflowExecutionState state) {
        if (StringUtils.hasText(state.contextText())) {
            return "Workflow fallback answer. Context: " + state.contextText();
        }
        return "Workflow fallback answer. Prompt: " + prompt;
    }

    private String renderTemplate(String template, WorkflowExecutionState state) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(resolveTemplateVariable(matcher.group(1),
                    state)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String resolveTemplateVariable(String name, WorkflowExecutionState state) {
        return switch (name) {
            case "input" -> state.primaryInput();
            case "context" -> state.contextText();
            case "lastOutput" -> state.lastOutput() == null ? "" : String.valueOf(state.lastOutput());
            case "toolResult" -> state.lastToolResult();
            default -> resolveDottedTemplateVariable(name, state);
        };
    }

    private String resolveDottedTemplateVariable(String name, WorkflowExecutionState state) {
        if (name.startsWith("input.")) {
            return stringValue(resolvePath(state.input(), name.substring("input.".length())));
        }
        if (name.startsWith("lastOutput.")) {
            return stringValue(resolvePath(state.lastOutput(), name.substring("lastOutput.".length())));
        }
        return "";
    }

    private Object resolvePath(Object root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String configString(WorkflowNode node, String key, String defaultValue) {
        Object value = node.config().get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private int configInt(WorkflowNode node, String key, int defaultValue) {
        Object value = node.config().get(key);
        if (value instanceof Number number) {
            return clamp(number.intValue(), 1, 20);
        }
        if (value != null) {
            try {
                return clamp(Integer.parseInt(String.valueOf(value)), 1, 20);
            }
            catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean configBoolean(WorkflowNode node, String key, boolean defaultValue) {
        Object value = node.config().get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

}
