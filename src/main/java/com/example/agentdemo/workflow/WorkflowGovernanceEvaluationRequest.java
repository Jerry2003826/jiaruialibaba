package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record WorkflowGovernanceEvaluationRequest(
        @NotNull WorkflowDefinition workflowDefinition,
        JsonNode lockedSpec,
        Map<String, Object> supplementalInput) {

    public WorkflowGovernanceEvaluationRequest {
        if (supplementalInput == null) {
            supplementalInput = Map.of();
        }
        else {
            supplementalInput = Collections.unmodifiableMap(new LinkedHashMap<>(supplementalInput));
        }
    }

    public void validateSupplementalInput() {
        supplementalInput.forEach((key, value) -> {
            if (key == null || value == null) {
                throw new BusinessException(
                        "VALIDATION_ERROR",
                        "supplementalInput must not contain null keys or values");
            }
        });
    }
}
