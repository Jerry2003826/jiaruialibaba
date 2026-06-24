package com.example.agentdemo.workflow;

import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.tool.ToolExecutionLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkflowExecutionState {

    private final Map<String, Object> input;
    private List<RetrievedContext> retrievedContext = List.of();
    private final List<ToolExecutionLog> toolCalls = new ArrayList<>();
    private final Map<String, Object> nodeOutputs = new LinkedHashMap<>();
    private Object lastOutput;
    private Object finalOutput;
    private String answer;
    private Boolean lastConditionResult;

    WorkflowExecutionState(Map<String, Object> input) {
        this.input = input;
    }

    Map<String, Object> input() {
        return input;
    }

    String primaryInput() {
        Object message = input.get("message");
        if (message == null) {
            message = input.get("query");
        }
        return message == null ? String.valueOf(input) : String.valueOf(message);
    }

    List<RetrievedContext> retrievedContext() {
        return retrievedContext;
    }

    void setRetrievedContext(List<RetrievedContext> retrievedContext) {
        this.retrievedContext = List.copyOf(retrievedContext);
    }

    String contextText() {
        List<String> snippets = retrievedContext.stream()
                .map(context -> context.title() + ": " + context.snippet())
                .toList();
        return String.join("\n", snippets);
    }

    List<ToolExecutionLog> toolCalls() {
        return List.copyOf(toolCalls);
    }

    void addToolCall(ToolExecutionLog toolCall) {
        this.toolCalls.add(toolCall);
    }

    String lastToolResult() {
        if (toolCalls.isEmpty()) {
            return "";
        }
        Object output = toolCalls.getLast().output();
        return output == null ? "" : String.valueOf(output);
    }

    Object lastOutput() {
        return lastOutput;
    }

    void setLastOutput(Object lastOutput) {
        this.lastOutput = lastOutput;
    }

    void recordNodeOutput(String nodeId) {
        this.nodeOutputs.put(nodeId, lastOutput);
    }

    Object nodeOutput(String nodeId) {
        return nodeOutputs.get(nodeId);
    }

    Map<String, Object> nodeOutputs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(nodeOutputs));
    }

    Boolean lastConditionResult() {
        return lastConditionResult;
    }

    void setLastConditionResult(Boolean lastConditionResult) {
        this.lastConditionResult = lastConditionResult;
    }

    Object finalOutput() {
        return finalOutput == null ? lastOutput : finalOutput;
    }

    void setFinalOutput(Object finalOutput) {
        this.finalOutput = finalOutput;
    }

    String answer() {
        return answer;
    }

    void setAnswer(String answer) {
        this.answer = answer;
    }

}
