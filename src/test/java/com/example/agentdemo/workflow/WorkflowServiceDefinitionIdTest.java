package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class WorkflowServiceDefinitionIdTest {

    @Test
    void runsWorkflowByPersistedDefinitionId() {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        when(definitionService.resolveDefinition("wf-1", null))
                .thenReturn(new WorkflowDefinitionResolution("wf-1", 7, definition));
        WorkflowRuntime runtime = mock(WorkflowRuntime.class);
        when(runtime.run(eq("run-1"), any(), eq(Map.of("message", "hello"))))
                .thenReturn(new WorkflowRuntime.WorkflowExecutionResult(Map.of("answer", "ok"), List.of()));
        TraceService traceService = mock(TraceService.class);
        when(traceService.createRun(eq(RunType.WORKFLOW), any()))
                .thenReturn(new RunEntity("run-1", RunType.WORKFLOW, RunStatus.RUNNING, "{}", Instant.now()));
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()), runtime,
                traceService, definitionService, runRecordRepository, runRepository);

        WorkflowRunResponse response = service.run(new WorkflowRunRequest(null, "wf-1", Map.of("message", "hello")));

        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.output()).isEqualTo(Map.of("answer", "ok"));
        assertThat(response.definitionId()).isEqualTo("wf-1");
        assertThat(response.definitionVersion()).isEqualTo(7);
        verify(definitionService).resolveDefinition("wf-1", null);
        ArgumentCaptor<Object> traceInputCaptor = ArgumentCaptor.forClass(Object.class);
        verify(traceService).createRun(eq(RunType.WORKFLOW), traceInputCaptor.capture());
        assertThat(traceInputCaptor.getValue())
                .isInstanceOfSatisfying(WorkflowRunTraceInput.class, traceInput -> {
                    assertThat(traceInput.definitionId()).isEqualTo("wf-1");
                    assertThat(traceInput.definitionVersion()).isEqualTo(7);
                });
        ArgumentCaptor<WorkflowRunRecordEntity> runRecordCaptor =
                ArgumentCaptor.forClass(WorkflowRunRecordEntity.class);
        verify(runRecordRepository).save(runRecordCaptor.capture());
        assertThat(runRecordCaptor.getValue().getRunId()).isEqualTo("run-1");
        assertThat(runRecordCaptor.getValue().getDefinitionId()).isEqualTo("wf-1");
        assertThat(runRecordCaptor.getValue().getDefinitionVersion()).isEqualTo(7);
        verify(traceService).markRunSucceeded(eq("run-1"), any());
    }

    @Test
    void runsWorkflowByPinnedDefinitionVersion() {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        when(definitionService.resolveDefinition("wf-1", 2))
                .thenReturn(new WorkflowDefinitionResolution("wf-1", 2, definition));
        WorkflowRuntime runtime = mock(WorkflowRuntime.class);
        when(runtime.run(eq("run-2"), any(), eq(Map.of("message", "pinned"))))
                .thenReturn(new WorkflowRuntime.WorkflowExecutionResult(Map.of("answer", "v2"), List.of()));
        TraceService traceService = mock(TraceService.class);
        when(traceService.createRun(eq(RunType.WORKFLOW), any()))
                .thenReturn(new RunEntity("run-2", RunType.WORKFLOW, RunStatus.RUNNING, "{}", Instant.now()));
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()), runtime,
                traceService, definitionService, runRecordRepository, runRepository);

        WorkflowRunResponse response = service.run(new WorkflowRunRequest(null, "wf-1", 2,
                Map.of("message", "pinned")));

        assertThat(response.runId()).isEqualTo("run-2");
        assertThat(response.output()).isEqualTo(Map.of("answer", "v2"));
        assertThat(response.definitionId()).isEqualTo("wf-1");
        assertThat(response.definitionVersion()).isEqualTo(2);
        verify(definitionService).resolveDefinition("wf-1", 2);
        ArgumentCaptor<Object> traceInputCaptor = ArgumentCaptor.forClass(Object.class);
        verify(traceService).createRun(eq(RunType.WORKFLOW), traceInputCaptor.capture());
        assertThat(traceInputCaptor.getValue())
                .isInstanceOfSatisfying(WorkflowRunTraceInput.class, traceInput -> {
                    assertThat(traceInput.definitionId()).isEqualTo("wf-1");
                    assertThat(traceInput.definitionVersion()).isEqualTo(2);
                });
        ArgumentCaptor<WorkflowRunRecordEntity> runRecordCaptor =
                ArgumentCaptor.forClass(WorkflowRunRecordEntity.class);
        verify(runRecordRepository).save(runRecordCaptor.capture());
        assertThat(runRecordCaptor.getValue().getDefinitionVersion()).isEqualTo(2);
    }

    @Test
    void listsRunsByDefinitionAndOptionalVersion() {
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        WorkflowRunRecordEntity v2 = new WorkflowRunRecordEntity("run-2", "wf-1", 2,
                Instant.parse("2026-06-24T04:00:00Z"));
        WorkflowRunRecordEntity v1 = new WorkflowRunRecordEntity("run-1", "wf-1", 1,
                Instant.parse("2026-06-24T03:00:00Z"));
        when(runRecordRepository.findAllByDefinitionIdOrderByStartedAtDesc("wf-1"))
                .thenReturn(List.of(v2, v1));
        when(runRecordRepository.findAllByDefinitionIdAndDefinitionVersionOrderByStartedAtDesc("wf-1", 2))
                .thenReturn(List.of(v2));
        RunEntity run2 = new RunEntity("run-2", RunType.WORKFLOW, RunStatus.SUCCEEDED, "{}", v2.getStartedAt());
        run2.setOutput("{\"answer\":\"v2\"}");
        run2.setEndedAt(Instant.parse("2026-06-24T04:00:03Z"));
        RunEntity run1 = new RunEntity("run-1", RunType.WORKFLOW, RunStatus.FAILED, "{}", v1.getStartedAt());
        run1.setErrorMessage("boom");
        run1.setEndedAt(Instant.parse("2026-06-24T03:00:02Z"));
        when(runRepository.findAllByRunIdIn(List.of("run-2", "run-1"))).thenReturn(List.of(run1, run2));
        when(runRepository.findAllByRunIdIn(List.of("run-2"))).thenReturn(List.of(run2));
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), mock(TraceService.class), mock(WorkflowDefinitionService.class),
                runRecordRepository, runRepository);

        List<WorkflowRunRecordResponse> allRuns = service.listRuns("wf-1", null);
        List<WorkflowRunRecordResponse> v2Runs = service.listRuns("wf-1", 2);

        assertThat(allRuns)
                .extracting(WorkflowRunRecordResponse::runId)
                .containsExactly("run-2", "run-1");
        assertThat(v2Runs)
                .extracting(WorkflowRunRecordResponse::runId)
                .containsExactly("run-2");
        assertThat(allRuns.get(0).status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(allRuns.get(0).output()).isEqualTo("{\"answer\":\"v2\"}");
        assertThat(allRuns.get(0).endedAt()).isEqualTo(Instant.parse("2026-06-24T04:00:03Z"));
        assertThat(allRuns.get(1).status()).isEqualTo(RunStatus.FAILED);
        assertThat(allRuns.get(1).errorMessage()).isEqualTo("boom");
    }

    @Test
    void marksRunFailedWhenRunRecordCannotBeSaved() {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        when(definitionService.resolveDefinition("wf-1", null))
                .thenReturn(new WorkflowDefinitionResolution("wf-1", 1, definition));
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        IllegalStateException failure = new IllegalStateException("metadata write failed");
        when(runRecordRepository.save(any(WorkflowRunRecordEntity.class))).thenThrow(failure);
        WorkflowRuntime runtime = mock(WorkflowRuntime.class);
        TraceService traceService = mock(TraceService.class);
        RunRepository runRepository = mock(RunRepository.class);
        when(traceService.createRun(eq(RunType.WORKFLOW), any()))
                .thenReturn(new RunEntity("run-3", RunType.WORKFLOW, RunStatus.RUNNING, "{}", Instant.now()));
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()), runtime,
                traceService, definitionService, runRecordRepository, runRepository);

        assertThatThrownBy(() -> service.run(new WorkflowRunRequest(null, "wf-1", Map.of("message", "hello"))))
                .isSameAs(failure);
        verify(traceService).markRunFailed("run-3", failure);
    }

    @Test
    void rejectsRunRequestWithoutInlineDefinitionOrDefinitionId() {
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), mock(TraceService.class), mock(WorkflowDefinitionService.class),
                mock(WorkflowRunRecordRepository.class), mock(RunRepository.class));

        assertThatThrownBy(() -> service.run(new WorkflowRunRequest(null, null, Map.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_DEFINITION_REQUIRED"));
    }

    private WorkflowDefinition validDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
    }

}
