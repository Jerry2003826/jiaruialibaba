package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowLoopBlock(String loopNodeId, String exitNodeId, List<String> bodyNodeIds, int maxIterations) {

    public WorkflowLoopBlock {
        bodyNodeIds = List.copyOf(bodyNodeIds);
    }

}
