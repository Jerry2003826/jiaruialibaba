package com.example.agentdemo.workflow;

import java.util.List;
import java.util.Locale;

final class WorkflowValidationSummaryFactory {

    private WorkflowValidationSummaryFactory() {
    }

    static WorkflowValidationSummary from(WorkflowDefinition definition, WorkflowExecutionPlan executionPlan) {
        List<String> nodeTypes = executionPlan.nodesById().values()
                .stream()
                .map(node -> node.type().toLowerCase(Locale.ROOT))
                .distinct()
                .toList();
        return new WorkflowValidationSummary(executionPlan.nodesById().size(), definition.edges().size(),
                executionPlan.linear(), executionPlan.startNode().id(), executionPlan.endNode().id(), nodeTypes);
    }

}
