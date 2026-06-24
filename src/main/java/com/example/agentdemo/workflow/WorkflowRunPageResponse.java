package com.example.agentdemo.workflow;

import java.util.List;

public record WorkflowRunPageResponse(
        List<WorkflowRunRecordResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
