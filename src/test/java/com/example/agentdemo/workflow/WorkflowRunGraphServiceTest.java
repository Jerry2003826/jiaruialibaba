package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowRunGraphServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowCompiler workflowCompiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());
    private final WorkflowGraphRenderer workflowGraphRenderer = new WorkflowGraphRenderer();

    @Test
    void getsSavedDefinitionWorkflowRunGraphWithTraceStatusOverlay() {
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
                runStep("run-graph", "step-start", "workflow_node_start", StepStatus.SUCCEEDED, null),
                runStep("run-graph", "step-check", "workflow_node_check_intent", StepStatus.SUCCEEDED, null),
                runStep("run-graph", "step-tool", "workflow_node_tool_time", StepStatus.SUCCEEDED, null),
                runStep("run-graph", "step-end", "workflow_node_end", StepStatus.SUCCEEDED, null)));
        WorkflowRunGraphService service = service(definitionService, runRecordRepository, traceService);

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
        verify(traceService, never()).startRun(any(), any());
        verify(traceService, never()).startTraceStep(any(), any(), any());
        verify(traceService, never()).completeStep(any(), any());
    }

    @Test
    void getsInlineDefinitionWorkflowRunGraphFromRunTraceInput() throws Exception {
        WorkflowDefinition definition = validDefinitionWithToolNode();
        WorkflowRunRequest request = new WorkflowRunRequest(definition, Map.of("message", "time"));
        String inputJson = objectMapper.writeValueAsString(new WorkflowRunTraceInput(request, null, null));
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        when(runRecordRepository.findById("inline-run")).thenReturn(Optional.empty());
        TraceService traceService = mock(TraceService.class);
        RunResponse run = new RunResponse("inline-run", RunType.WORKFLOW, RunStatus.SUCCEEDED, inputJson,
                "{\"answer\":\"ok\"}", null, Instant.parse("2026-06-24T05:20:00Z"),
                Instant.parse("2026-06-24T05:20:03Z"));
        when(traceService.getRun("inline-run")).thenReturn(run);
        when(traceService.listSteps("inline-run")).thenReturn(List.of(
                runStep("inline-run", "step-start", "workflow_node_start", StepStatus.SUCCEEDED, null),
                runStep("inline-run", "step-tool", "workflow_node_tool_time", StepStatus.SUCCEEDED, null),
                runStep("inline-run", "step-end", "workflow_node_end", StepStatus.SUCCEEDED, null)));
        WorkflowRunGraphService service = service(runRecordRepository, traceService);

        WorkflowRunGraphResponse response = service.getRunGraph("inline-run");

        assertThat(response.runId()).isEqualTo("inline-run");
        assertThat(response.definitionId()).isNull();
        assertThat(response.definitionVersion()).isNull();
        assertThat(response.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(response.nodes())
                .extracting(WorkflowRunGraphNodeView::id)
                .containsExactly("start", "tool_time", "end");
        assertThat(response.nodes())
                .allSatisfy(node -> assertThat(node.executed()).isTrue());
        assertThat(response.mermaid()).contains("tool_time (tool) SUCCEEDED");
        verify(traceService, never()).startRun(any(), any());
        verify(traceService, never()).startTraceStep(any(), any(), any());
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
                runStep("run-failed", "step-start", "workflow_node_start", StepStatus.SUCCEEDED, null),
                runStep("run-failed", "step-tool", "workflow_node_tool_time", StepStatus.FAILED, "tool failed")));
        WorkflowRunGraphService service = service(definitionService, runRecordRepository, traceService);

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
    void rejectsGraphForNonWorkflowRunWithoutSavedMetadata() {
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        when(runRecordRepository.findById("chat-run")).thenReturn(Optional.empty());
        TraceService traceService = mock(TraceService.class);
        RunResponse run = new RunResponse("chat-run", RunType.CHAT, RunStatus.SUCCEEDED, "{\"message\":\"hi\"}",
                "{\"answer\":\"ok\"}", null, Instant.parse("2026-06-24T05:30:00Z"),
                Instant.parse("2026-06-24T05:30:02Z"));
        when(traceService.getRun("chat-run")).thenReturn(run);
        WorkflowRunGraphService service = service(runRecordRepository, traceService);

        assertThatThrownBy(() -> service.getRunGraph("chat-run"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_GRAPH_UNAVAILABLE"))
                .hasMessage("Workflow graph is unavailable for run: chat-run");
        verify(traceService, never()).listSteps("chat-run");
    }

    @Test
    void rejectsGraphWhenInlineWorkflowRunTraceDoesNotContainDefinition() throws Exception {
        WorkflowRunRequest request = new WorkflowRunRequest(null, "wf-missing-record", Map.of("message", "hi"));
        String inputJson = objectMapper.writeValueAsString(new WorkflowRunTraceInput(request, "wf-missing-record", 4));
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        when(runRecordRepository.findById("stale-run")).thenReturn(Optional.empty());
        TraceService traceService = mock(TraceService.class);
        RunResponse run = new RunResponse("stale-run", RunType.WORKFLOW, RunStatus.SUCCEEDED, inputJson,
                "{\"answer\":\"ok\"}", null, Instant.parse("2026-06-24T05:40:00Z"),
                Instant.parse("2026-06-24T05:40:02Z"));
        when(traceService.getRun("stale-run")).thenReturn(run);
        WorkflowRunGraphService service = service(runRecordRepository, traceService);

        assertThatThrownBy(() -> service.getRunGraph("stale-run"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_GRAPH_UNAVAILABLE"))
                .hasMessage("Workflow graph is unavailable for run: stale-run");
        verify(traceService, never()).listSteps("stale-run");
    }

    @Test
    void rejectsGraphWhenInlineWorkflowRunTraceInputIsBlank() {
        WorkflowRunRecordRepository runRecordRepository = mock(WorkflowRunRecordRepository.class);
        when(runRecordRepository.findById("blank-run")).thenReturn(Optional.empty());
        TraceService traceService = mock(TraceService.class);
        RunResponse run = new RunResponse("blank-run", RunType.WORKFLOW, RunStatus.SUCCEEDED, "",
                "{\"answer\":\"ok\"}", null, Instant.parse("2026-06-24T05:45:00Z"),
                Instant.parse("2026-06-24T05:45:02Z"));
        when(traceService.getRun("blank-run")).thenReturn(run);
        WorkflowRunGraphService service = service(runRecordRepository, traceService);

        assertThatThrownBy(() -> service.getRunGraph("blank-run"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_GRAPH_UNAVAILABLE"))
                .hasMessage("Workflow graph is unavailable for run: blank-run");
        verify(traceService, never()).listSteps("blank-run");
    }

    @Test
    void mapsMissingTraceRunToWorkflowRunNotFound() {
        TraceService traceService = mock(TraceService.class);
        when(traceService.getRun("missing-run"))
                .thenThrow(new BusinessException("RUN_NOT_FOUND", "Run not found: missing-run"));
        WorkflowRunGraphService service = service(mock(WorkflowRunRecordRepository.class), traceService);

        assertThatThrownBy(() -> service.getRunGraph("missing-run"))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_RUN_NOT_FOUND"))
                .hasMessage("Workflow run not found: missing-run");
        verify(traceService, never()).listSteps("missing-run");
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

    private RunStepResponse runStep(String runId, String stepId, String nodeName, StepStatus status,
            String errorMessage) {
        return new RunStepResponse(stepId, runId, nodeName, "{}", "{}", errorMessage, status,
                Instant.parse("2026-06-24T05:00:01Z"), Instant.parse("2026-06-24T05:00:02Z"));
    }

    private WorkflowRunGraphService service(WorkflowRunRecordRepository runRecordRepository,
            TraceService traceService) {
        return service(mock(WorkflowDefinitionService.class), runRecordRepository, traceService);
    }

    private WorkflowRunGraphService service(WorkflowDefinitionService definitionService,
            WorkflowRunRecordRepository runRecordRepository, TraceService traceService) {
        return new WorkflowRunGraphService(workflowCompiler, definitionService, runRecordRepository, traceService,
                workflowGraphRenderer, objectMapper);
    }

}
