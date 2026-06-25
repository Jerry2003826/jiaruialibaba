package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
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

@Component
public class WorkflowNodeExecutor {

    private static final String WORKFLOW_SYSTEM_PROMPT = """
            You are a workflow LLM node. Use the workflow input and retrieved context when available.
            If context is missing, say what is missing instead of inventing details.
            """;

    private final RagService ragService;
    private final AiModelService aiModelService;
    private final ToolGatewayService toolGatewayService;
    private final WorkflowVariableResolver variableResolver;
    private final AlibabaRuntimePolicy alibabaRuntimePolicy;
    private final WorkflowInlineExecutionService inlineExecutionService;

    public WorkflowNodeExecutor(RagService ragService, AiModelService aiModelService,
            ToolGatewayService toolGatewayService, WorkflowVariableResolver variableResolver,
            AlibabaRuntimePolicy alibabaRuntimePolicy, WorkflowInlineExecutionService inlineExecutionService) {
        this.ragService = ragService;
        this.aiModelService = aiModelService;
        this.toolGatewayService = toolGatewayService;
        this.variableResolver = variableResolver;
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
        this.inlineExecutionService = inlineExecutionService;
    }

    Object execute(String runId, WorkflowNode node, WorkflowExecutionState state) {
        return switch (normalizeType(node)) {
            case "start" -> executeStart(state);
            case "retriever" -> executeRetriever(runId, node, state);
            case "llm" -> executeLlm(node, state);
            case "tool" -> executeTool(node, state);
            case "condition" -> executeCondition(node, state);
            case "parallel" -> executeParallel(state);
            case "join" -> executeJoin(state);
            case "end" -> executeEnd(state);
            case "loop" -> inlineExecutionService.executeLoop(runId, node, state);
            case "loop_back" -> executeLoopBack(node, state);
            case "subgraph" -> inlineExecutionService.executeSubgraph(runId, node, state);
            case "dynamic" -> inlineExecutionService.executeDynamic(runId, node, state);
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
        input.put("nodeOutputs", state.nodeOutputs());
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
        String prompt = variableResolver.renderString(promptTemplate, state);
        AiModelResult result = aiModelService.generate(WORKFLOW_SYSTEM_PROMPT, prompt);
        String answer = resolveAnswer(prompt, state, result);
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
        String left = variableResolver.renderString(configString(node, "left", "{{input}}"), state);
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

    private Object executeParallel(WorkflowExecutionState state) {
        Map<String, Object> output = orderedMap();
        output.put("status", "READY");
        output.put("input", state.lastOutput());
        state.setLastOutput(output);
        return output;
    }

    private Object executeJoin(WorkflowExecutionState state) {
        Object output = state.lastOutput() == null ? Map.of() : state.lastOutput();
        state.setLastOutput(output);
        return output;
    }

    private Object executeLoopBack(WorkflowNode node, WorkflowExecutionState state) {
        Map<String, Object> output = orderedMap();
        output.put("status", "LOOP_BACK");
        output.put("nodeId", node.id());
        state.setLastOutput(output);
        return output;
    }

    boolean evaluateCondition(String left, String operator, Object right, boolean caseSensitive) {
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
            case "greaterthan" -> compareNumbers(leftText, rightText) > 0;
            case "lessthan" -> compareNumbers(leftText, rightText) < 0;
            default -> throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Unsupported condition operator: " + operator);
        };
    }

    private int compareNumbers(String leftText, String rightText) {
        try {
            double leftValue = Double.parseDouble(leftText);
            double rightValue = Double.parseDouble(rightText);
            return Double.compare(leftValue, rightValue);
        }
        catch (NumberFormatException ex) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Numeric comparison requires numeric left/right values");
        }
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
            arguments.put("expression", variableResolver.renderString(configString(node, "expression", ""), state));
        }
        return arguments;
    }

    private Object renderArgument(Object value, WorkflowExecutionState state) {
        return variableResolver.renderValue(value, state);
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

    private String resolveAnswer(String prompt, WorkflowExecutionState state, AiModelResult result) {
        if (!result.fallback()) {
            return result.answer();
        }
        if (alibabaRuntimePolicy.isStrictMode()) {
            throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                    "Alibaba LLM is required for workflow LLM nodes: " + result.errorMessage());
        }
        return fallbackAnswer(prompt, state);
    }

    private String fallbackAnswer(String prompt, WorkflowExecutionState state) {
        if (StringUtils.hasText(state.contextText())) {
            return "Workflow fallback answer. Context: " + state.contextText();
        }
        return "Workflow fallback answer. Prompt: " + prompt;
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
