package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
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
    void listsRunsByDefinitionVersionStatusAndPage() {
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        WorkflowRunRecordEntity v2 = new WorkflowRunRecordEntity("run-2", "wf-1", 2,
                Instant.parse("2026-06-24T04:00:00Z"));
        PageRequest pageable = PageRequest.of(1, 1, Sort.by(Sort.Direction.DESC, "startedAt"));
        when(runRecordRepository.searchRuns("wf-1", 2, RunStatus.SUCCEEDED, pageable))
                .thenReturn(new PageImpl<>(List.of(v2), pageable, 2));
        RunEntity run2 = new RunEntity("run-2", RunType.WORKFLOW, RunStatus.SUCCEEDED, "{}", v2.getStartedAt());
        run2.setOutput("{\"answer\":\"v2\"}");
        run2.setEndedAt(Instant.parse("2026-06-24T04:00:03Z"));
        when(runRepository.findAllByRunIdIn(List.of("run-2"))).thenReturn(List.of(run2));
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), mock(TraceService.class), mock(WorkflowDefinitionService.class),
                runRecordRepository, runRepository);

        WorkflowRunPageResponse response = service.listRuns("wf-1", 2, RunStatus.SUCCEEDED, 1, 1);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.totalElements()).isEqualTo(2);
        assertThat(response.totalPages()).isEqualTo(2);
        assertThat(response.content())
                .extracting(WorkflowRunRecordResponse::runId)
                .containsExactly("run-2");
        assertThat(response.content().getFirst().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(response.content().getFirst().output()).isEqualTo("{\"answer\":\"v2\"}");
        assertThat(response.content().getFirst().endedAt()).isEqualTo(Instant.parse("2026-06-24T04:00:03Z"));
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
    void getsWorkflowRunDetailWithTraceSteps() {
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        RunRepository runRepository = mock(RunRepository.class);
        TraceService traceService = mock(TraceService.class);
        WorkflowRunRecordEntity record = new WorkflowRunRecordEntity("run-1", "wf-1", 2,
                Instant.parse("2026-06-24T04:00:00Z"));
        when(runRecordRepository.findById("run-1")).thenReturn(Optional.of(record));
        RunResponse run = new RunResponse("run-1", RunType.WORKFLOW, RunStatus.SUCCEEDED, "{\"input\":\"hi\"}",
                "{\"answer\":\"ok\"}", null, record.getStartedAt(), Instant.parse("2026-06-24T04:00:03Z"));
        RunStepResponse step = new RunStepResponse("step-1", "run-1", "llm_1", "{}", "{\"answer\":\"ok\"}", null,
                StepStatus.SUCCEEDED, Instant.parse("2026-06-24T04:00:01Z"),
                Instant.parse("2026-06-24T04:00:02Z"));
        when(traceService.getRun("run-1")).thenReturn(run);
        when(traceService.listSteps("run-1")).thenReturn(List.of(step));
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), traceService, mock(WorkflowDefinitionService.class), runRecordRepository,
                runRepository);

        WorkflowRunDetailResponse response = service.getRunDetail("run-1");

        assertThat(response.summary().runId()).isEqualTo("run-1");
        assertThat(response.summary().definitionId()).isEqualTo("wf-1");
        assertThat(response.summary().definitionVersion()).isEqualTo(2);
        assertThat(response.summary().status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(response.run()).isEqualTo(run);
        assertThat(response.steps()).containsExactly(step);
    }

    @Test
    void getsWorkflowRunGraphWithTraceStatusOverlay() {
        WorkflowDefinition definition = branchingDefinition();
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        when(definitionService.resolveDefinition("wf-1", 2))
                .thenReturn(new WorkflowDefinitionResolution("wf-1", 2, definition));
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        WorkflowRunRecordEntity record = new WorkflowRunRecordEntity("run-graph", "wf-1", 2,
                Instant.parse("2026-06-24T05:00:00Z"));
        when(runRecordRepository.findById("run-graph")).thenReturn(Optional.of(record));
        TraceService traceService = mock(TraceService.class);
        RunResponse run = new RunResponse("run-graph", RunType.WORKFLOW, RunStatus.SUCCEEDED, "{}",
                "{\"answer\":\"ok\"}", null, record.getStartedAt(), Instant.parse("2026-06-24T05:00:04Z"));
        when(traceService.getRun("run-graph")).thenReturn(run);
        when(traceService.listSteps("run-graph")).thenReturn(List.of(
                runStep("step-start", "workflow_node_start", StepStatus.SUCCEEDED, null),
                runStep("step-check", "workflow_node_check_intent", StepStatus.SUCCEEDED, null),
                runStep("step-tool", "workflow_node_tool_time", StepStatus.SUCCEEDED, null),
                runStep("step-end", "workflow_node_end", StepStatus.SUCCEEDED, null)));
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), traceService, definitionService, runRecordRepository,
                mock(RunRepository.class));

        WorkflowRunGraphResponse response = service.getRunGraph("run-graph");

        assertThat(response.runId()).isEqualTo("run-graph");
        assertThat(response.definitionId()).isEqualTo("wf-1");
        assertThat(response.definitionVersion()).isEqualTo(2);
        assertThat(response.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(response.summary())
                .isEqualTo(new WorkflowValidationSummary(5, 5, false, "start", "end",
                        List.of("start", "condition", "tool", "llm", "end")));
        assertThat(response.nodes())
                .extracting(WorkflowRunGraphNodeView::id)
                .containsExactly("start", "check_intent", "tool_time", "llm_fallback", "end");
        assertThat(response.nodes().get(2).executed()).isTrue();
        assertThat(response.nodes().get(2).status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(response.nodes().get(2).stepId()).isEqualTo("step-tool");
        assertThat(response.nodes().get(3).executed()).isFalse();
        assertThat(response.nodes().get(3).status()).isNull();
        assertThat(response.edges())
                .contains(
                        new WorkflowRunGraphEdgeView("check_intent", "tool_time", "true", "true", true),
                        new WorkflowRunGraphEdgeView("check_intent", "llm_fallback", "false", "false", false));
        assertThat(response.mermaid())
                .contains("tool_time (tool) SUCCEEDED")
                .contains("llm_fallback (llm) NOT_EXECUTED")
                .contains("n1 -- \"true\" --> n2")
                .contains("n1 -. \"false\" .-> n3")
                .contains("class n2 succeeded")
                .contains("class n3 notExecuted");
        verify(traceService, never()).createRun(any(), any());
        verify(traceService, never()).startStep(any(), any(), any());
        verify(traceService, never()).completeStep(any(), any());
    }

    @Test
    void getsWorkflowRunGraphWithFailedStepError() {
        WorkflowDefinition definition = validDefinitionWithToolNode();
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        when(definitionService.resolveDefinition("wf-1", 3))
                .thenReturn(new WorkflowDefinitionResolution("wf-1", 3, definition));
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        WorkflowRunRecordEntity record = new WorkflowRunRecordEntity("run-failed", "wf-1", 3,
                Instant.parse("2026-06-24T05:10:00Z"));
        when(runRecordRepository.findById("run-failed")).thenReturn(Optional.of(record));
        TraceService traceService = mock(TraceService.class);
        RunResponse run = new RunResponse("run-failed", RunType.WORKFLOW, RunStatus.FAILED, "{}", "{}",
                "tool failed", record.getStartedAt(), Instant.parse("2026-06-24T05:10:03Z"));
        when(traceService.getRun("run-failed")).thenReturn(run);
        when(traceService.listSteps("run-failed")).thenReturn(List.of(
                runStep("step-start", "workflow_node_start", StepStatus.SUCCEEDED, null),
                runStep("step-tool", "workflow_node_tool_time", StepStatus.FAILED, "tool failed")));
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), traceService, definitionService, runRecordRepository,
                mock(RunRepository.class));

        WorkflowRunGraphResponse response = service.getRunGraph("run-failed");

        WorkflowRunGraphNodeView toolNode = response.nodes()
                .stream()
                .filter(node -> node.id().equals("tool_time"))
                .findFirst()
                .orElseThrow();
        assertThat(toolNode.executed()).isTrue();
        assertThat(toolNode.status()).isEqualTo(StepStatus.FAILED);
        assertThat(toolNode.errorMessage()).isEqualTo("tool failed");
        assertThat(response.mermaid()).contains("class n1 failed");
    }

    @Test
    void throwsWhenWorkflowRunGraphRecordIsMissing() {
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        when(runRecordRepository.findById("missing-run")).thenReturn(Optional.empty());
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), mock(TraceService.class), mock(WorkflowDefinitionService.class),
                runRecordRepository, mock(RunRepository.class));

        assertThatThrownBy(() -> service.getRunGraph("missing-run"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_RUN_NOT_FOUND"))
                .hasMessage("Workflow run not found: missing-run");
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

    @Test
    void validatesWorkflowDefinitionWithoutCreatingRunTrace() {
        TraceService traceService = mock(TraceService.class);
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), traceService, mock(WorkflowDefinitionService.class),
                mock(WorkflowRunRecordRepository.class), mock(RunRepository.class));

        WorkflowValidationResponse response = service.validate(new WorkflowValidationRequest(validDefinition()));

        assertThat(response.valid()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.summary())
                .isEqualTo(new WorkflowValidationSummary(2, 1, true, "start", "end", List.of("start", "end")));
        verifyNoInteractions(traceService);
    }

    @Test
    void returnsValidationErrorForInvalidWorkflowDefinition() {
        TraceService traceService = mock(TraceService.class);
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), traceService, mock(WorkflowDefinitionService.class),
                mock(WorkflowRunRecordRepository.class), mock(RunRepository.class));
        WorkflowDefinition invalidDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool", "tool", Map.of("unexpected", true)),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool"),
                        new WorkflowEdge("tool", "end")));

        WorkflowValidationResponse response = service.validate(new WorkflowValidationRequest(invalidDefinition));

        assertThat(response.valid()).isFalse();
        assertThat(response.summary()).isNull();
        assertThat(response.errors())
                .containsExactly(new WorkflowValidationError("WORKFLOW_VALIDATION_FAILED",
                        "Unsupported config key for node tool: unexpected"));
        verifyNoInteractions(traceService);
    }

    private WorkflowDefinition validDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
    }

    private WorkflowDefinition validDefinitionWithToolNode() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_time", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_time"),
                        new WorkflowEdge("tool_time", "end")));
    }

    private WorkflowDefinition branchingDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("check_intent", "condition",
                                Map.of("left", "{{input.intent}}", "operator", "equals", "right", "time")),
                        new WorkflowNode("tool_time", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("llm_fallback", "llm", Map.of("prompt", "answer {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "check_intent"),
                        new WorkflowEdge("check_intent", "tool_time", "true"),
                        new WorkflowEdge("check_intent", "llm_fallback", "false"),
                        new WorkflowEdge("tool_time", "end"),
                        new WorkflowEdge("llm_fallback", "end")));
    }

    private RunStepResponse runStep(String stepId, String nodeName, StepStatus status, String errorMessage) {
        return new RunStepResponse(stepId, "run-graph", nodeName, "{}", "{}", errorMessage, status,
                Instant.parse("2026-06-24T05:00:01Z"), Instant.parse("2026-06-24T05:00:02Z"));
    }

}
