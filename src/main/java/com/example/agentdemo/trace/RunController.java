package com.example.agentdemo.trace;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.trace.dto.RunPageResponse;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import com.example.agentdemo.usage.UsageRecordingService;
import com.example.agentdemo.usage.UsageSummaryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final TraceService traceService;
    private final UsageRecordingService usageRecordingService;

    public RunController(TraceService traceService, UsageRecordingService usageRecordingService) {
        this.traceService = traceService;
        this.usageRecordingService = usageRecordingService;
    }

    @GetMapping
    public ApiResponse<RunPageResponse> listRuns(
            @RequestParam(required = false) RunType type,
            @RequestParam(required = false) RunStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(traceService.listRuns(type, status, page, size));
    }

    @GetMapping("/{runId}")
    public ApiResponse<RunResponse> getRun(@PathVariable String runId) {
        return ApiResponse.ok(traceService.getRun(runId));
    }

    @GetMapping("/{runId}/steps")
    public ApiResponse<List<RunStepResponse>> listSteps(@PathVariable String runId) {
        return ApiResponse.ok(traceService.listSteps(runId));
    }

    @GetMapping("/{runId}/usage")
    public ApiResponse<UsageSummaryResponse> usage(@PathVariable String runId) {
        traceService.getRun(runId);
        return ApiResponse.ok(usageRecordingService.summarize(runId));
    }

}
