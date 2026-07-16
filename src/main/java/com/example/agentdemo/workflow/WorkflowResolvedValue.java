package com.example.agentdemo.workflow;

record WorkflowResolvedValue(boolean present, Object value) {

    private static final WorkflowResolvedValue MISSING = new WorkflowResolvedValue(false, null);

    static WorkflowResolvedValue missing() {
        return MISSING;
    }

    static WorkflowResolvedValue present(Object value) {
        return new WorkflowResolvedValue(true, value);
    }
}
