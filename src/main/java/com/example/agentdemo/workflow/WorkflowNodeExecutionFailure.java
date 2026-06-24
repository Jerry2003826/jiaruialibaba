package com.example.agentdemo.workflow;

final class WorkflowNodeExecutionFailure extends RuntimeException {

    private final RuntimeException original;
    private final Object traceOutput;
    private final Object summaryOutput;

    WorkflowNodeExecutionFailure(RuntimeException original, Object traceOutput, Object summaryOutput) {
        super(original.getMessage(), original);
        this.original = original;
        this.traceOutput = traceOutput;
        this.summaryOutput = summaryOutput;
    }

    RuntimeException original() {
        return original;
    }

    Object traceOutput() {
        return traceOutput;
    }

    Object summaryOutput() {
        return summaryOutput;
    }

}
