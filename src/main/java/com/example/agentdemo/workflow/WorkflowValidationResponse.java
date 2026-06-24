package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowValidationResponse(
        boolean valid,
        List<WorkflowValidationError> errors,
        WorkflowValidationSummary summary) {

    public WorkflowValidationResponse {
        errors = List.copyOf(errors);
    }

    static WorkflowValidationResponse valid(WorkflowValidationSummary summary) {
        return new WorkflowValidationResponse(true, List.of(), summary);
    }

    static WorkflowValidationResponse invalid(String code, String message) {
        return new WorkflowValidationResponse(false, List.of(new WorkflowValidationError(code, message)), null);
    }

}
