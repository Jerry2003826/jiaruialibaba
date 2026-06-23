package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolService;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.TraceService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SimpleWorkflowRuntime {

    private static final String WORKFLOW_SYSTEM_PROMPT = """
            You are a workflow LLM node. Use the workflow input and retrieved context when available.
            If context is missing, say what is missing instead of inventing details.
            """;

    private final RagService ragService;
    private final AiModelService aiModelService;
    private final ToolService toolService;
    private final TraceService traceService;

    public SimpleWorkflowRuntime(RagService ragService, AiModelService aiModelService, ToolService toolService,
            TraceService traceService) {
        this.ragService = ragService;
        this.aiModelService = aiModelService;
        this.toolService = toolService;
        this.traceService = traceService;
    }

    public WorkflowExecutionResult run(String runId, List<WorkflowNode> orderedNodes, Map<String, Object> input) {
        WorkflowExecutionState state = new WorkflowExecutionState(input);
        List<WorkflowStepSummary> summaries = new ArrayList<>();
        for (WorkflowNode node : orderedNodes) {
            RunStepEntity step = traceService.startStep(runId, "workflow_node_" + node.id(),
                    nodeInput(node, state));
            try {
                Object output = executeNode(node, state);
                traceService.completeStep(step.getStepId(), output);
                summaries.add(new WorkflowStepSummary(node.id(), normalizeType(node), "SUCCEEDED", output));
            }
            catch (RuntimeException ex) {
                traceService.failStep(step.getStepId(), ex);
                summaries.add(new WorkflowStepSummary(node.id(), normalizeType(node), "FAILED", errorOutput(ex)));
                throw ex;
            }
        }
        return new WorkflowExecutionResult(state.finalOutput(), summaries);
    }

    private Object executeNode(WorkflowNode node, WorkflowExecutionState state) {
        return switch (normalizeType(node)) {
            case "start" -> executeStart(state);
            case "retriever" -> executeRetriever(node, state);
            case "llm" -> executeLlm(node, state);
            case "tool" -> executeTool(node, state);
            case "end" -> executeEnd(state);
            default -> throw new BusinessException("WORKFLOW_UNSUPPORTED", "Unsupported node type: " + node.type());
        };
    }

    private Object executeStart(WorkflowExecutionState state) {
        state.setLastOutput(state.input());
        return state.input();
    }

    private Object executeRetriever(WorkflowNode node, WorkflowExecutionState state) {
        String query = configString(node, "query", state.primaryInput());
        int topK = configInt(node, "topK", 3);
        List<RetrievedContext> contexts = ragService.retrieve(query, topK);
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
        ToolExecutionLog log = switch (toolName) {
            case "getCurrentTime" -> toolService.executeGetCurrentTime();
            case "calculate" -> toolService.executeCalculate(renderTemplate(configString(node, "expression", ""), state));
            default -> throw new BusinessException("WORKFLOW_UNSUPPORTED", "Unsupported workflow tool: " + toolName);
        };
        state.addToolCall(log);
        if (!log.succeeded()) {
            throw new IllegalArgumentException(log.errorMessage());
        }
        state.setLastOutput(log.output());
        return log;
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

    private Map<String, Object> nodeInput(WorkflowNode node, WorkflowExecutionState state) {
        Map<String, Object> input = orderedMap();
        input.put("nodeId", node.id());
        input.put("nodeType", normalizeType(node));
        input.put("config", node.config());
        input.put("workflowInput", state.input());
        input.put("lastOutput", state.lastOutput());
        return input;
    }

    private String renderTemplate(String template, WorkflowExecutionState state) {
        return template
                .replace("{{input}}", state.primaryInput())
                .replace("{{context}}", state.contextText())
                .replace("{{lastOutput}}", state.lastOutput() == null ? "" : String.valueOf(state.lastOutput()))
                .replace("{{toolResult}}", state.lastToolResult());
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private String normalizeType(WorkflowNode node) {
        return node.type().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    private Map<String, Object> errorOutput(RuntimeException ex) {
        Map<String, Object> output = orderedMap();
        output.put("error", ex.getMessage());
        return output;
    }

    public record WorkflowExecutionResult(Object output, List<WorkflowStepSummary> steps) {
    }

    private static final class WorkflowExecutionState {

        private final Map<String, Object> input;
        private List<RetrievedContext> retrievedContext = List.of();
        private List<ToolExecutionLog> toolCalls = new ArrayList<>();
        private Object lastOutput;
        private Object finalOutput;
        private String answer;

        private WorkflowExecutionState(Map<String, Object> input) {
            this.input = input;
        }

        private Map<String, Object> input() {
            return input;
        }

        private String primaryInput() {
            Object message = input.get("message");
            if (message == null) {
                message = input.get("query");
            }
            return message == null ? String.valueOf(input) : String.valueOf(message);
        }

        private List<RetrievedContext> retrievedContext() {
            return retrievedContext;
        }

        private void setRetrievedContext(List<RetrievedContext> retrievedContext) {
            this.retrievedContext = List.copyOf(retrievedContext);
        }

        private String contextText() {
            List<String> snippets = retrievedContext.stream()
                    .map(context -> context.title() + ": " + context.snippet())
                    .toList();
            return String.join("\n", snippets);
        }

        private List<ToolExecutionLog> toolCalls() {
            return List.copyOf(toolCalls);
        }

        private void addToolCall(ToolExecutionLog toolCall) {
            this.toolCalls.add(toolCall);
        }

        private String lastToolResult() {
            if (toolCalls.isEmpty()) {
                return "";
            }
            Object output = toolCalls.getLast().output();
            return output == null ? "" : String.valueOf(output);
        }

        private Object lastOutput() {
            return lastOutput;
        }

        private void setLastOutput(Object lastOutput) {
            this.lastOutput = lastOutput;
        }

        private Object finalOutput() {
            return finalOutput == null ? lastOutput : finalOutput;
        }

        private void setFinalOutput(Object finalOutput) {
            this.finalOutput = finalOutput;
        }

        private String answer() {
            return answer;
        }

        private void setAnswer(String answer) {
            this.answer = answer;
        }

    }

}
