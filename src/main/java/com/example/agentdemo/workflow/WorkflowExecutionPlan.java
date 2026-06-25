package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record WorkflowExecutionPlan(
        WorkflowNode startNode,
        WorkflowNode endNode,
        Map<String, WorkflowNode> nodesById,
        Map<String, List<WorkflowExecutionEdge>> outgoingEdges,
        Map<String, List<String>> incomingNodeIds,
        List<WorkflowNode> linearNodes,
        List<WorkflowParallelBlock> parallelBlocks,
        List<WorkflowLoopBlock> loopBlocks,
        Set<String> compositeScopedNodeIds) {

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
        loopBlocks = List.copyOf(loopBlocks);
        compositeScopedNodeIds = Set.copyOf(compositeScopedNodeIds);
    }

    public WorkflowExecutionPlan(WorkflowNode startNode, WorkflowNode endNode, Map<String, WorkflowNode> nodesById,
            Map<String, List<WorkflowExecutionEdge>> outgoingEdges, Map<String, List<String>> incomingNodeIds,
            List<WorkflowNode> linearNodes, List<WorkflowParallelBlock> parallelBlocks) {
        this(startNode, endNode, nodesById, outgoingEdges, incomingNodeIds, linearNodes, parallelBlocks, List.of(),
                Set.of());
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

    public WorkflowLoopBlock loopBlock(String loopNodeId) {
        return loopBlocks.stream()
                .filter(block -> block.loopNodeId().equals(loopNodeId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("WORKFLOW_UNSUPPORTED",
                        "Missing compiled loop block: " + loopNodeId));
    }

    public boolean isCompositeScopedNode(String nodeId) {
        return compositeScopedNodeIds.contains(nodeId);
    }

    public boolean isCompositeContainerNode(String nodeId) {
        WorkflowNode node = nodesById.get(nodeId);
        if (node == null) {
            return false;
        }
        String type = node.type().toLowerCase();
        return "loop".equals(type) || "subgraph".equals(type) || "dynamic".equals(type);
    }

}
