package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowParallelBlock(
        String parallelNodeId,
        String joinNodeId,
        List<WorkflowBranchPath> branches) {

    public WorkflowParallelBlock {
        branches = List.copyOf(branches);
    }

}
