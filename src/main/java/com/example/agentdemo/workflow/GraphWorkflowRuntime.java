package com.example.agentdemo.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.TraceService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class GraphWorkflowRuntime implements WorkflowRuntime {

    private static final String NODE_OUTPUT_STATE_KEY = "lastNodeOutput";

    private final WorkflowNodeExecutor nodeExecutor;
    private final TraceService traceService;

    public GraphWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService) {
        this.nodeExecutor = nodeExecutor;
        this.traceService = traceService;
    }

    @Override
    public WorkflowExecutionResult run(String runId, WorkflowExecutionPlan executionPlan, Map<String, Object> input) {
        WorkflowExecutionState state = new WorkflowExecutionState(input);
        List<WorkflowStepSummary> summaries = new ArrayList<>();
        try {
            CompiledGraph graph = compileGraph(runId, executionPlan, state, summaries);
            graph.invoke(Map.of("workflowInput", input));
            return new WorkflowExecutionResult(state.finalOutput(), summaries);
        }
        catch (GraphStateException ex) {
            throw new BusinessException("WORKFLOW_GRAPH_FAILED", "Failed to compile Spring AI Alibaba Graph", ex);
        }
        catch (RuntimeException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new BusinessException("WORKFLOW_GRAPH_FAILED", "Failed to run Spring AI Alibaba Graph", ex);
        }
    }

    private CompiledGraph compileGraph(String runId, WorkflowExecutionPlan executionPlan, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries) throws GraphStateException {
        StateGraph graph = new StateGraph("workflow-" + runId,
                KeyStrategy.builder().defaultStrategy(KeyStrategy.REPLACE).build());
        for (WorkflowNode node : executionPlan.nodesById().values()) {
            graph.addNode(node.id(), AsyncNodeAction.node_async(overAllState ->
                    executeGraphNode(runId, node, state, summaries)));
        }
        graph.addEdge(StateGraph.START, executionPlan.startNode().id());
        for (WorkflowNode node : executionPlan.nodesById().values()) {
            if (node.id().equals(executionPlan.endNode().id())) {
                continue;
            }
            addOutgoingEdges(graph, node, executionPlan.outgoing(node.id()), state);
        }
        graph.addEdge(executionPlan.endNode().id(), StateGraph.END);
        return graph.compile();
    }

    private void addOutgoingEdges(StateGraph graph, WorkflowNode node, List<WorkflowExecutionEdge> outgoing,
            WorkflowExecutionState state) throws GraphStateException {
        if (outgoing.size() == 1 && !outgoing.getFirst().conditional()) {
            graph.addEdge(node.id(), outgoing.getFirst().to());
            return;
        }
        Map<String, String> mappings = new LinkedHashMap<>();
        for (WorkflowExecutionEdge edge : outgoing) {
            mappings.put(edge.condition(), edge.to());
        }
        graph.addConditionalEdges(node.id(),
                ignored -> CompletableFuture.completedFuture(conditionRouteKey(node, state)),
                mappings);
    }

    private String conditionRouteKey(WorkflowNode node, WorkflowExecutionState state) {
        Boolean conditionResult = state.lastConditionResult();
        if (conditionResult == null) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Condition node did not produce a boolean result: " + node.id());
        }
        return conditionResult.toString();
    }

    private Map<String, Object> executeGraphNode(String runId, WorkflowNode node, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries) {
        RunStepEntity step = traceService.startStep(runId, "workflow_node_" + node.id(),
                nodeExecutor.nodeInput(node, state));
        try {
            Object output = nodeExecutor.execute(runId, node, state);
            state.recordNodeOutput(node.id());
            traceService.completeStep(step.getStepId(), output);
            summaries.add(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "SUCCEEDED", output));
            return Map.of(NODE_OUTPUT_STATE_KEY, output == null ? "" : output);
        }
        catch (RuntimeException ex) {
            Object failureOutput = failureOutput(ex);
            traceService.failStep(step.getStepId(), ex, failureOutput);
            summaries.add(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "FAILED", failureOutput));
            throw ex;
        }
    }

    private Object failureOutput(RuntimeException ex) {
        if (ex instanceof WorkflowNodeExecutionException nodeException) {
            return nodeException.output();
        }
        return nodeExecutor.errorOutput(ex);
    }

}
