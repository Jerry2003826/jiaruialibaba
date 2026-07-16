package com.example.agentdemo.workflow;

import jakarta.validation.Valid;

import java.util.List;

public record WorkflowDefinition(
        List<@Valid WorkflowNode> nodes,
        List<@Valid WorkflowEdge> edges) {

    public WorkflowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
    }
}
