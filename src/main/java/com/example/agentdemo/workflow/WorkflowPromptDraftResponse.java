package com.example.agentdemo.workflow;

import java.util.Map;

public record WorkflowPromptDraftResponse(
        String instruction,
        String outputMode,
        Map<String, Object> outputSchema,
        Map<String, Object> writeState) {

    public WorkflowPromptDraftResponse(String instruction) {
        this(instruction, "text", Map.of(), Map.of());
    }
}
