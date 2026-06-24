package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;

public class WorkflowNodeExecutionException extends BusinessException {

    private final Object output;

    public WorkflowNodeExecutionException(String code, String message, Object output) {
        super(code == null || code.isBlank() ? "WORKFLOW_NODE_FAILED" : code, message);
        this.output = output;
    }

    public Object output() {
        return output;
    }

}
