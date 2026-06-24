package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;

import java.util.List;

public record WorkflowRunDetailResponse(
        WorkflowRunRecordResponse summary,
        RunResponse run,
        List<RunStepResponse> steps) {
}
