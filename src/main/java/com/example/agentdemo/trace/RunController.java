package com.example.agentdemo.trace;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final TraceService traceService;

    public RunController(TraceService traceService) {
        this.traceService = traceService;
    }

    @GetMapping
    public ApiResponse<List<RunResponse>> listRuns() {
        return ApiResponse.ok(traceService.listRuns());
    }

    @GetMapping("/{runId}")
    public ApiResponse<RunResponse> getRun(@PathVariable String runId) {
        return ApiResponse.ok(traceService.getRun(runId));
    }

    @GetMapping("/{runId}/steps")
    public ApiResponse<List<RunStepResponse>> listSteps(@PathVariable String runId) {
        return ApiResponse.ok(traceService.listSteps(runId));
    }

}
