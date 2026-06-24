package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.common.BusinessException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SimpleWorkflowRuntime implements WorkflowRuntime {

    private final WorkflowNodeExecutor nodeExecutor;
    private final TraceService traceService;

    public SimpleWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService) {
        this.nodeExecutor = nodeExecutor;
        this.traceService = traceService;
    }

    @Override
    public WorkflowExecutionResult run(String runId, WorkflowExecutionPlan executionPlan, Map<String, Object> input) {
        WorkflowExecutionState state = new WorkflowExecutionState(input);
        List<WorkflowStepSummary> summaries = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        WorkflowNode node = executionPlan.startNode();
        while (node != null) {
            if (!visited.add(node.id())) {
                throw new BusinessException("WORKFLOW_UNSUPPORTED", "Workflow cycles are not supported");
            }
            executeWithTrace(runId, node, state, summaries);
            if (node.id().equals(executionPlan.endNode().id())) {
                break;
            }
            node = nextNode(executionPlan, node, state);
        }
        return new WorkflowExecutionResult(state.finalOutput(), summaries);
    }

    private WorkflowNode nextNode(WorkflowExecutionPlan executionPlan, WorkflowNode current,
            WorkflowExecutionState state) {
        List<WorkflowExecutionEdge> outgoing = executionPlan.outgoing(current.id());
        if (outgoing.isEmpty()) {
            throw new BusinessException("WORKFLOW_UNSUPPORTED",
                    "Workflow node has no outgoing edge: " + current.id());
        }
        if (outgoing.size() == 1 && !outgoing.getFirst().conditional()) {
            return executionPlan.node(outgoing.getFirst().to());
        }
        Boolean conditionResult = state.lastConditionResult();
        for (WorkflowExecutionEdge edge : outgoing) {
            if (edge.matches(conditionResult)) {
                return executionPlan.node(edge.to());
            }
        }
        throw new BusinessException("WORKFLOW_UNSUPPORTED",
                "No matching workflow edge for condition node: " + current.id());
    }

    private void executeWithTrace(String runId, WorkflowNode node, WorkflowExecutionState state,
            List<WorkflowStepSummary> summaries) {
        RunStepEntity step = traceService.startStep(runId, "workflow_node_" + node.id(),
                nodeExecutor.nodeInput(node, state));
        try {
            Object output = nodeExecutor.execute(runId, node, state);
            state.recordNodeOutput(node.id());
            traceService.completeStep(step.getStepId(), output);
            summaries.add(new WorkflowStepSummary(node.id(), nodeExecutor.normalizeType(node), "SUCCEEDED", output));
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
