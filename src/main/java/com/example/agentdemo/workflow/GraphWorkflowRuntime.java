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
import java.util.List;
import java.util.Map;

public class GraphWorkflowRuntime implements WorkflowRuntime {

    private static final String NODE_OUTPUT_STATE_KEY = "lastNodeOutput";

    private final WorkflowNodeExecutor nodeExecutor;
    private final TraceService traceService;

    public GraphWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService) {
        this.nodeExecutor = nodeExecutor;
        this.traceService = traceService;
    }

    @Override
    public WorkflowExecutionResult run(String runId, List<WorkflowNode> orderedNodes, Map<String, Object> input) {
        WorkflowExecutionState state = new WorkflowExecutionState(input);
        List<WorkflowStepSummary> summaries = new ArrayList<>();
        try {
            CompiledGraph graph = compileGraph(runId, orderedNodes, state, summaries);
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

    private CompiledGraph compileGraph(String runId, List<WorkflowNode> orderedNodes, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries) throws GraphStateException {
        StateGraph graph = new StateGraph("workflow-" + runId,
                KeyStrategy.builder().defaultStrategy(KeyStrategy.REPLACE).build());
        for (WorkflowNode node : orderedNodes) {
            graph.addNode(node.id(), AsyncNodeAction.node_async(overAllState ->
                    executeGraphNode(runId, node, state, summaries)));
        }
        graph.addEdge(StateGraph.START, orderedNodes.getFirst().id());
        for (int i = 0; i < orderedNodes.size() - 1; i++) {
            graph.addEdge(orderedNodes.get(i).id(), orderedNodes.get(i + 1).id());
        }
        graph.addEdge(orderedNodes.getLast().id(), StateGraph.END);
        return graph.compile();
    }

    private Map<String, Object> executeGraphNode(String runId, WorkflowNode node, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries) {
        RunStepEntity step = traceService.startStep(runId, "workflow_node_" + node.id(),
                nodeExecutor.nodeInput(node, state));
        try {
            Object output = nodeExecutor.execute(runId, node, state);
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
