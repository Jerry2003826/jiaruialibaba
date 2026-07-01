package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives workflow run events from the owner-scoped trace (run + steps). The snapshot method is kept
 * for replay/test callers; the SSE endpoint uses delta cursors so repeated polls only process new or
 * still-pending steps.
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

    public WorkflowRunEventsSnapshot delta(String runId, WorkflowRunEventCursor cursor) {
        WorkflowRunEventCursor effectiveCursor = cursor == null ? new WorkflowRunEventCursor() : cursor;
        RunResponse run = traceService.getRun(runId);
        boolean terminal = run.status() != RunStatus.RUNNING;
        List<RunStepResponse> steps = deltaSteps(runId, effectiveCursor, terminal);
        List<WorkflowRunEvent> events = new ArrayList<>();
        for (RunStepResponse step : steps) {
            if (effectiveCursor.markStepStarted(step.stepId())) {
                events.add(new WorkflowRunEvent("node_started", nodeData(step, null)));
            }
            WorkflowRunEvent terminalEvent = terminalStepEvent(step, effectiveCursor);
            if (terminalEvent != null) {
                events.add(terminalEvent);
            }
            effectiveCursor.advanceTo(step.startedAt(), step.stepId());
        }
        if (terminal && effectiveCursor.markRunDoneSent()) {
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("runId", runId);
            done.put("status", run.status().name());
            events.add(new WorkflowRunEvent("run_done", done));
        }
        return new WorkflowRunEventsSnapshot(events, terminal, run.status());
    }

    private List<RunStepResponse> deltaSteps(String runId, WorkflowRunEventCursor cursor, boolean terminal) {
        List<RunStepResponse> pending = traceService.findSteps(runId, cursor.pendingStepIds());
        List<RunStepResponse> fresh = traceService.listStepsAfter(runId, cursor.lastStartedAt(), cursor.lastStepId());
        Map<String, RunStepResponse> byStepId = new LinkedHashMap<>();
        for (RunStepResponse step : pending) {
            byStepId.put(step.stepId(), step);
        }
        for (RunStepResponse step : fresh) {
            byStepId.put(step.stepId(), step);
        }
        if (!terminal && hasUnseenStartedStep(runId, cursor, byStepId)) {
            for (RunStepResponse step : traceService.listSteps(runId)) {
                byStepId.putIfAbsent(step.stepId(), step);
            }
        }
        else if (terminal && !cursor.runDoneSent()) {
            for (RunStepResponse step : traceService.listSteps(runId)) {
                byStepId.putIfAbsent(step.stepId(), step);
            }
        }
        return byStepId.values().stream()
                .sorted(Comparator.comparing(RunStepResponse::startedAt).thenComparing(RunStepResponse::stepId))
                .toList();
    }

    private boolean hasUnseenStartedStep(String runId, WorkflowRunEventCursor cursor,
            Map<String, RunStepResponse> candidates) {
        long newStartedCandidates = candidates.keySet().stream()
                .filter(stepId -> !cursor.stepStartedSent(stepId))
                .count();
        return traceService.countSteps(runId) > cursor.sentStartedCount() + newStartedCandidates;
    }

    private WorkflowRunEvent terminalStepEvent(RunStepResponse step, WorkflowRunEventCursor cursor) {
        if (step.status() == StepStatus.SUCCEEDED && cursor.markStepTerminal(step.stepId())) {
            return new WorkflowRunEvent("node_succeeded", nodeData(step, null));
        }
        if (step.status() == StepStatus.FAILED && cursor.markStepTerminal(step.stepId())) {
            return new WorkflowRunEvent("node_failed", nodeData(step, step.errorMessage()));
        }
        return null;
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
