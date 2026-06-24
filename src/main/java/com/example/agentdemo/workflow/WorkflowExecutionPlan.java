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
        Map<String, List<String>> incomingNodeIds,
        List<WorkflowNode> linearNodes,
        List<WorkflowParallelBlock> parallelBlocks) {

    public WorkflowExecutionPlan {
        nodesById = Collections.unmodifiableMap(new LinkedHashMap<>(nodesById));
        Map<String, List<WorkflowExecutionEdge>> outgoingCopy = new LinkedHashMap<>();
        for (Map.Entry<String, List<WorkflowExecutionEdge>> entry : outgoingEdges.entrySet()) {
            outgoingCopy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        outgoingEdges = Collections.unmodifiableMap(outgoingCopy);
        Map<String, List<String>> incomingCopy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : incomingNodeIds.entrySet()) {
            incomingCopy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        incomingNodeIds = Collections.unmodifiableMap(incomingCopy);
        linearNodes = List.copyOf(linearNodes);
        parallelBlocks = List.copyOf(parallelBlocks);
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

    public List<String> incomingNodeIds(String nodeId) {
        return incomingNodeIds.getOrDefault(nodeId, List.of());
    }

    public boolean hasParallelJoin() {
        return !parallelBlocks.isEmpty();
    }

    public WorkflowParallelBlock parallelBlock(String parallelNodeId) {
        return parallelBlocks.stream()
                .filter(block -> block.parallelNodeId().equals(parallelNodeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Missing compiled parallel block: " + parallelNodeId));
    }

}
