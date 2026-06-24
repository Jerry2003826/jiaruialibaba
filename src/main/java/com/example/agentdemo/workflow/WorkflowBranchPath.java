package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowBranchPath(
        String branchStartNodeId,
        String joinNodeId,
        List<String> nodeIds) {

    public WorkflowBranchPath {
        nodeIds = List.copyOf(nodeIds);
    }

}
