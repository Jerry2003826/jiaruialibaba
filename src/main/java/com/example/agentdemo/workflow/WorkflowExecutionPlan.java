package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record WorkflowExecutionPlan(
        WorkflowNode startNode,
        WorkflowNode endNode,
        Map<String, WorkflowNode> nodesById,
        Map<String, List<WorkflowExecutionEdge>> outgoingEdges,
        List<WorkflowNode> linearNodes) {

    public WorkflowExecutionPlan {
        nodesById = Collections.unmodifiableMap(new LinkedHashMap<>(nodesById));
        Map<String, List<WorkflowExecutionEdge>> outgoingCopy = new LinkedHashMap<>();
        for (Map.Entry<String, List<WorkflowExecutionEdge>> entry : outgoingEdges.entrySet()) {
            outgoingCopy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        outgoingEdges = Collections.unmodifiableMap(outgoingCopy);
        linearNodes = List.copyOf(linearNodes);
    }

    public boolean linear() {
        return !linearNodes.isEmpty() && linearNodes.size() == nodesById.size();
    }

    public WorkflowNode node(String nodeId) {
        WorkflowNode node = nodesById.get(nodeId);
        if (node == null) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED", "Missing workflow node: " + nodeId);
        }
        return node;
    }

    public List<WorkflowExecutionEdge> outgoing(String nodeId) {
        return outgoingEdges.getOrDefault(nodeId, List.of());
    }

}
