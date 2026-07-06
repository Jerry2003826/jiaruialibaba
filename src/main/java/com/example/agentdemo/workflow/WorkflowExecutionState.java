package com.example.agentdemo.workflow;

import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.tool.ToolExecutionLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkflowExecutionState {

    private final Map<String, Object> input;
    private List<RetrievedContext> retrievedContext = List.of();
    private final List<ToolExecutionLog> toolCalls = new ArrayList<>();
    private final Map<String, Object> nodeOutputs = new LinkedHashMap<>();
    private final Map<String, Object> stateVariables = new LinkedHashMap<>();
    private Object lastOutput;
    private Object finalOutput;
    private String answer;
    private Boolean lastConditionResult;
    private final Map<String, Integer> loopIterations = new HashMap<>();

    WorkflowExecutionState(Map<String, Object> input) {
        this.input = input;
    }

    private WorkflowExecutionState(WorkflowExecutionState source) {
        this.input = source.input;
        this.retrievedContext = source.retrievedContext;
        this.toolCalls.addAll(source.toolCalls);
        this.nodeOutputs.putAll(source.nodeOutputs);
        this.stateVariables.putAll(source.stateVariables);
        this.lastOutput = source.lastOutput;
        this.finalOutput = source.finalOutput;
        this.answer = source.answer;
        this.lastConditionResult = source.lastConditionResult;
        this.loopIterations.putAll(source.loopIterations);
    }

    WorkflowExecutionState copyForBranch() {
        return new WorkflowExecutionState(this);
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

    int toolCallCount() {
        return toolCalls.size();
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

    Map<String, Object> stateVariables() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(stateVariables));
    }

    void setStateVariable(String name, Object value) {
        stateVariables.put(name, value);
    }

    void mergeBranchState(WorkflowExecutionState branchState, int baseToolCallCount) {
        this.nodeOutputs.putAll(branchState.nodeOutputs);
        this.stateVariables.putAll(branchState.stateVariables);
        if (branchState.toolCalls.size() > baseToolCallCount) {
            this.toolCalls.addAll(branchState.toolCalls.subList(baseToolCallCount, branchState.toolCalls.size()));
        }
        if (!branchState.retrievedContext.isEmpty()) {
            this.retrievedContext = branchState.retrievedContext;
        }
        if (branchState.answer != null) {
            this.answer = branchState.answer;
        }
    }

    void setParallelBranchOutputs(Map<String, Object> branchOutputs) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("branchOutputs", Collections.unmodifiableMap(new LinkedHashMap<>(branchOutputs)));
        setLastOutput(Collections.unmodifiableMap(output));
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

    int loopIteration(String loopNodeId) {
        return loopIterations.getOrDefault(loopNodeId, 0);
    }

    void incrementLoopIteration(String loopNodeId) {
        loopIterations.put(loopNodeId, loopIteration(loopNodeId) + 1);
    }

}
