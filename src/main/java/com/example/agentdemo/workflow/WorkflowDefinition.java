package com.example.agentdemo.workflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record WorkflowDefinition(
        @NotEmpty List<@Valid WorkflowNode> nodes,
        @NotEmpty List<@Valid WorkflowEdge> edges) {
}
