package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class WorkflowRunEventServiceTest {

    private static final Instant STARTED_AT = Instant.parse("2026-07-01T12:00:00Z");

    @Test
    void deltaDoesNotRepeatStepEventsOrRunDoneAcrossPolls() {
        TraceService traceService = mock(TraceService.class);
        WorkflowRunEventService service = new WorkflowRunEventService(traceService);
        WorkflowRunEventCursor cursor = new WorkflowRunEventCursor();
        RunStepResponse step = step("step-1", STARTED_AT, StepStatus.SUCCEEDED);
        when(traceService.getRun("run-1"))
                .thenReturn(run(RunStatus.RUNNING), run(RunStatus.RUNNING),
                        run(RunStatus.SUCCEEDED), run(RunStatus.SUCCEEDED));
        when(traceService.listStepsAfter("run-1", null, null)).thenReturn(List.of(step));
        when(traceService.listStepsAfter("run-1", STARTED_AT, "step-1")).thenReturn(List.of());
        when(traceService.countSteps("run-1")).thenReturn(1L);
        when(traceService.listSteps("run-1")).thenReturn(List.of(step));
        when(traceService.findSteps("run-1", Set.of())).thenReturn(List.of());

        WorkflowRunEventsSnapshot first = service.delta("run-1", cursor);
        WorkflowRunEventsSnapshot second = service.delta("run-1", cursor);
        WorkflowRunEventsSnapshot third = service.delta("run-1", cursor);
        WorkflowRunEventsSnapshot fourth = service.delta("run-1", cursor);

        assertThat(first.events()).extracting(WorkflowRunEvent::event)
                .containsExactly("node_started", "node_succeeded");
        assertThat(second.events()).isEmpty();
        assertThat(third.events()).extracting(WorkflowRunEvent::event).containsExactly("run_done");
        assertThat(fourth.events()).isEmpty();
        verify(traceService).listSteps("run-1");
    }

    @Test
    void deltaUsesStartedAtAndStepIdCursorForStepsInSameMillisecond() {
        TraceService traceService = mock(TraceService.class);
        WorkflowRunEventService service = new WorkflowRunEventService(traceService);
        WorkflowRunEventCursor cursor = new WorkflowRunEventCursor();
        when(traceService.getRun("run-1")).thenReturn(run(RunStatus.RUNNING), run(RunStatus.RUNNING));
        when(traceService.listStepsAfter("run-1", null, null))
                .thenReturn(List.of(step("step-a", STARTED_AT, StepStatus.SUCCEEDED)));
        when(traceService.listStepsAfter("run-1", STARTED_AT, "step-a"))
                .thenReturn(List.of(step("step-b", STARTED_AT, StepStatus.SUCCEEDED)));
        when(traceService.countSteps("run-1")).thenReturn(1L, 2L);
        when(traceService.findSteps("run-1", Set.of())).thenReturn(List.of());

        service.delta("run-1", cursor);
        WorkflowRunEventsSnapshot second = service.delta("run-1", cursor);

        assertThat(second.events()).extracting(event -> event.data().get("stepId"))
                .containsExactly("step-b", "step-b");
        verify(traceService).listStepsAfter("run-1", STARTED_AT, "step-a");
    }

    @Test
    void deltaUsesVisibleStepCountToReconcileLateVisibleLowerStepIdWhileRunning() {
        TraceService traceService = mock(TraceService.class);
        WorkflowRunEventService service = new WorkflowRunEventService(traceService);
        WorkflowRunEventCursor cursor = new WorkflowRunEventCursor();
        RunStepResponse firstVisible = step("step-z", STARTED_AT, StepStatus.SUCCEEDED);
        RunStepResponse lateLower = step("step-a", STARTED_AT, StepStatus.SUCCEEDED);
        when(traceService.getRun("run-1")).thenReturn(run(RunStatus.RUNNING), run(RunStatus.RUNNING));
        when(traceService.listStepsAfter("run-1", null, null)).thenReturn(List.of(firstVisible));
        when(traceService.listStepsAfter("run-1", STARTED_AT, "step-z")).thenReturn(List.of());
        when(traceService.countSteps("run-1")).thenReturn(1L, 2L);
        when(traceService.listSteps("run-1")).thenReturn(List.of(lateLower, firstVisible));
        when(traceService.findSteps("run-1", Set.of())).thenReturn(List.of());

        service.delta("run-1", cursor);
        WorkflowRunEventsSnapshot second = service.delta("run-1", cursor);

        assertThat(second.events()).extracting(event -> event.data().get("stepId"))
                .containsExactly("step-a", "step-a");
        verify(traceService).listStepsAfter("run-1", STARTED_AT, "step-z");
        verify(traceService).listSteps("run-1");
    }

    @Test
    void deltaReconcilesLateVisibleUnsentStepBeforeRunDone() {
        TraceService traceService = mock(TraceService.class);
        WorkflowRunEventService service = new WorkflowRunEventService(traceService);
        WorkflowRunEventCursor cursor = new WorkflowRunEventCursor();
        when(traceService.getRun("run-1")).thenReturn(run(RunStatus.RUNNING), run(RunStatus.SUCCEEDED));
        RunStepResponse lateStep = step("step-a", STARTED_AT.minusMillis(1), StepStatus.SUCCEEDED);
        RunStepResponse visibleStep = step("step-z", STARTED_AT, StepStatus.SUCCEEDED);
        when(traceService.listStepsAfter("run-1", null, null))
                .thenReturn(List.of(visibleStep));
        when(traceService.listStepsAfter("run-1", STARTED_AT, "step-z"))
                .thenReturn(List.of());
        when(traceService.countSteps("run-1")).thenReturn(1L);
        when(traceService.listSteps("run-1"))
                .thenReturn(List.of(lateStep, visibleStep));
        when(traceService.findSteps("run-1", Set.of())).thenReturn(List.of());

        service.delta("run-1", cursor);
        WorkflowRunEventsSnapshot second = service.delta("run-1", cursor);

        assertThat(second.events()).extracting(WorkflowRunEvent::event)
                .containsExactly("node_started", "node_succeeded", "run_done");
        assertThat(second.events()).extracting(event -> event.data().get("stepId"))
                .containsExactly("step-a", "step-a", null);
    }

    @Test
    void deltaRechecksPendingRunningStepsUntilTerminalEventIsSent() {
        TraceService traceService = mock(TraceService.class);
        WorkflowRunEventService service = new WorkflowRunEventService(traceService);
        WorkflowRunEventCursor cursor = new WorkflowRunEventCursor();
        RunStepResponse runningStep = step("step-1", STARTED_AT, StepStatus.RUNNING);
        RunStepResponse succeededStep = step("step-1", STARTED_AT, StepStatus.SUCCEEDED);
        when(traceService.getRun("run-1")).thenReturn(run(RunStatus.RUNNING), run(RunStatus.RUNNING));
        when(traceService.listStepsAfter("run-1", null, null)).thenReturn(List.of(runningStep));
        when(traceService.listStepsAfter("run-1", STARTED_AT, "step-1")).thenReturn(List.of());
        when(traceService.countSteps("run-1")).thenReturn(1L);
        when(traceService.findSteps("run-1", Set.of("step-1"))).thenReturn(List.of(succeededStep));
        when(traceService.findSteps("run-1", Set.of())).thenReturn(List.of());

        WorkflowRunEventsSnapshot first = service.delta("run-1", cursor);
        WorkflowRunEventsSnapshot second = service.delta("run-1", cursor);

        assertThat(first.events()).extracting(WorkflowRunEvent::event).containsExactly("node_started");
        assertThat(second.events()).extracting(WorkflowRunEvent::event).containsExactly("node_succeeded");
    }

    @Test
    void deltaSendsPendingTerminalStepBeforeRunDoneWhenRunEnds() {
        TraceService traceService = mock(TraceService.class);
        WorkflowRunEventService service = new WorkflowRunEventService(traceService);
        WorkflowRunEventCursor cursor = new WorkflowRunEventCursor();
        RunStepResponse runningStep = step("step-1", STARTED_AT, StepStatus.RUNNING);
        RunStepResponse succeededStep = step("step-1", STARTED_AT, StepStatus.SUCCEEDED);
        when(traceService.getRun("run-1")).thenReturn(run(RunStatus.RUNNING), run(RunStatus.SUCCEEDED));
        when(traceService.listStepsAfter("run-1", null, null)).thenReturn(List.of(runningStep));
        when(traceService.listStepsAfter("run-1", STARTED_AT, "step-1")).thenReturn(List.of());
        when(traceService.countSteps("run-1")).thenReturn(1L);
        when(traceService.listSteps("run-1")).thenReturn(List.of(succeededStep));
        when(traceService.findSteps("run-1", Set.of("step-1"))).thenReturn(List.of(succeededStep));
        when(traceService.findSteps("run-1", Set.of())).thenReturn(List.of());

        service.delta("run-1", cursor);
        WorkflowRunEventsSnapshot second = service.delta("run-1", cursor);

        assertThat(second.events()).extracting(WorkflowRunEvent::event)
                .containsExactly("node_succeeded", "run_done");
    }

    @Test
    void deltaDoesNotUseSnapshotStepListingWhileRunIsStillRunning() {
        TraceService traceService = mock(TraceService.class);
        WorkflowRunEventService service = new WorkflowRunEventService(traceService);
        WorkflowRunEventCursor cursor = new WorkflowRunEventCursor();
        when(traceService.getRun("run-1")).thenReturn(run(RunStatus.RUNNING));
        when(traceService.listStepsAfter("run-1", null, null))
                .thenReturn(List.of(step("step-z", STARTED_AT, StepStatus.SUCCEEDED)));
        when(traceService.countSteps("run-1")).thenReturn(1L);
        when(traceService.findSteps("run-1", Set.of())).thenReturn(List.of());

        service.delta("run-1", cursor);

        verify(traceService, never()).listSteps("run-1");
    }

    private RunResponse run(RunStatus status) {
        return new RunResponse("run-1", RunType.WORKFLOW, status, "{}", "{}", null, STARTED_AT, STARTED_AT);
    }

    private RunStepResponse step(String stepId, Instant startedAt, StepStatus status) {
        return new RunStepResponse(stepId, "run-1", "workflow_node_" + stepId, "{}", "{}", null,
                status, startedAt, startedAt.plusMillis(1));
    }

}
