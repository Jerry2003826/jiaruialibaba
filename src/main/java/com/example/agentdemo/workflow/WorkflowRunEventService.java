package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives workflow run events from the owner-scoped trace (run + steps). Because runs are executed
 * synchronously, this is a replay/poll model: each snapshot maps steps to node events and appends
 * {@code run_done} once the run is terminal. The SSE endpoint polls this until the run finishes.
 */
@Service
public class WorkflowRunEventService {

    private final TraceService traceService;

    public WorkflowRunEventService(TraceService traceService) {
        this.traceService = traceService;
    }

    public WorkflowRunEventsSnapshot snapshot(String runId) {
        RunResponse run = traceService.getRun(runId);
        List<RunStepResponse> steps = traceService.listSteps(runId);
        List<WorkflowRunEvent> events = new ArrayList<>();
        for (RunStepResponse step : steps) {
            events.add(new WorkflowRunEvent("node_started", nodeData(step, null)));
            if (step.status() == StepStatus.SUCCEEDED) {
                events.add(new WorkflowRunEvent("node_succeeded", nodeData(step, null)));
            }
            else if (step.status() == StepStatus.FAILED) {
                events.add(new WorkflowRunEvent("node_failed", nodeData(step, step.errorMessage())));
            }
        }
        boolean terminal = run.status() != RunStatus.RUNNING;
        if (terminal) {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("runId", runId);
            done.put("status", run.status().name());
            events.add(new WorkflowRunEvent("run_done", done));
        }
        return new WorkflowRunEventsSnapshot(events, terminal, run.status());
    }

    private Map<String, Object> nodeData(RunStepResponse step, String error) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stepId", step.stepId());
        data.put("nodeName", step.nodeName());
        data.put("status", step.status().name());
        if (error != null) {
            data.put("error", error);
        }
        return data;
    }

}
