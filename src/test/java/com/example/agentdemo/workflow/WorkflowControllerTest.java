package com.example.agentdemo.workflow;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.dto.RunResponse;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowControllerTest {

    @Test
    void listsWorkflowNodeSchemas() {
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class),
                mock(WorkflowDefinitionService.class), new WorkflowNodeSchemaRegistry());

        ApiResponse<List<WorkflowNodeSchema>> response = controller.listNodeSchemas();

        assertThat(response.success()).isTrue();
        assertThat(response.data())
                .extracting(WorkflowNodeSchema::type)
                .containsExactly("start", "retriever", "llm", "tool", "condition", "end");
    }

    @Test
    void validatesWorkflowDefinition() {
        WorkflowService workflowService = mock(WorkflowService.class);
        WorkflowValidationRequest request = new WorkflowValidationRequest(validDefinition());
        WorkflowValidationResponse expected = WorkflowValidationResponse.valid(
                new WorkflowValidationSummary(2, 1, true, "start", "end", List.of("start", "end")));
        when(workflowService.validate(request)).thenReturn(expected);
        WorkflowController controller = new WorkflowController(workflowService, mock(WorkflowDefinitionService.class),
                new WorkflowNodeSchemaRegistry());

        ApiResponse<WorkflowValidationResponse> response = controller.validate(request);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    void savesWorkflowDefinition() {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse expected = new WorkflowDefinitionResponse("wf-1", "Support Bot", null,
                definition, 1, WorkflowDefinitionStatus.DRAFT, null, null);
        WorkflowDefinitionSaveRequest request = new WorkflowDefinitionSaveRequest("Support Bot", null, definition);
        when(definitionService.save(request)).thenReturn(expected);
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class), definitionService,
                new WorkflowNodeSchemaRegistry());

        ApiResponse<WorkflowDefinitionResponse> response = controller.saveDefinition(request);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    void listsWorkflowDefinitions() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse expected = new WorkflowDefinitionResponse("wf-1", "Support Bot", null,
                validDefinition(), 1, WorkflowDefinitionStatus.DRAFT, null, null);
        when(definitionService.list()).thenReturn(List.of(expected));
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class), definitionService,
                new WorkflowNodeSchemaRegistry());

        ApiResponse<List<WorkflowDefinitionResponse>> response = controller.listDefinitions();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsExactly(expected);
    }

    @Test
    void getsWorkflowDefinition() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse expected = new WorkflowDefinitionResponse("wf-1", "Support Bot", null,
                validDefinition(), 1, WorkflowDefinitionStatus.DRAFT, null, null);
        when(definitionService.get("wf-1")).thenReturn(expected);
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class), definitionService,
                new WorkflowNodeSchemaRegistry());

        ApiResponse<WorkflowDefinitionResponse> response = controller.getDefinition("wf-1");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    void updatesWorkflowDefinition() {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionSaveRequest request = new WorkflowDefinitionSaveRequest("Support Bot v2", null, definition);
        WorkflowDefinitionResponse expected = new WorkflowDefinitionResponse("wf-1", "Support Bot v2", null,
                definition, 2, WorkflowDefinitionStatus.DRAFT, null, null);
        when(definitionService.update("wf-1", request)).thenReturn(expected);
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class), definitionService,
                new WorkflowNodeSchemaRegistry());

        ApiResponse<WorkflowDefinitionResponse> response = controller.updateDefinition("wf-1", request);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    void publishesWorkflowDefinition() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse expected = new WorkflowDefinitionResponse("wf-1", "Support Bot", null,
                validDefinition(), 1, WorkflowDefinitionStatus.PUBLISHED, null, null);
        when(definitionService.publish("wf-1")).thenReturn(expected);
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class), definitionService,
                new WorkflowNodeSchemaRegistry());

        ApiResponse<WorkflowDefinitionResponse> response = controller.publishDefinition("wf-1");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    void listsWorkflowDefinitionRevisions() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionRevisionResponse expected = new WorkflowDefinitionRevisionResponse("wf-1", 1,
                WorkflowDefinitionStatus.DRAFT, "Support Bot", null, validDefinition(), null, null);
        when(definitionService.listRevisions("wf-1")).thenReturn(List.of(expected));
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class), definitionService,
                new WorkflowNodeSchemaRegistry());

        ApiResponse<List<WorkflowDefinitionRevisionResponse>> response = controller.listDefinitionRevisions("wf-1");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsExactly(expected);
    }

    @Test
    void rollsBackWorkflowDefinition() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse expected = new WorkflowDefinitionResponse("wf-1", "Support Bot", null,
                validDefinition(), 3, WorkflowDefinitionStatus.DRAFT, null, null);
        when(definitionService.rollback("wf-1", 1)).thenReturn(expected);
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class), definitionService,
                new WorkflowNodeSchemaRegistry());

        ApiResponse<WorkflowDefinitionResponse> response = controller.rollbackDefinition("wf-1", 1);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(expected);
    }

    @Test
    void deletesWorkflowDefinition() {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class), definitionService,
                new WorkflowNodeSchemaRegistry());

        ApiResponse<Void> response = controller.deleteDefinition("wf-1");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
        verify(definitionService).delete("wf-1");
    }

    @Test
    void listsWorkflowRunsByDefinition() {
        WorkflowService workflowService = mock(WorkflowService.class);
        WorkflowRunRecordResponse expected = new WorkflowRunRecordResponse("run-1", "wf-1", 2,
                Instant.parse("2026-06-24T04:00:00Z"), RunStatus.SUCCEEDED, "{\"answer\":\"ok\"}", null,
                Instant.parse("2026-06-24T04:00:03Z"));
        WorkflowRunPageResponse page = new WorkflowRunPageResponse(List.of(expected), 1, 5, 6, 2);
        when(workflowService.listRuns("wf-1", 2, RunStatus.SUCCEEDED, 1, 5)).thenReturn(page);
        WorkflowController controller = new WorkflowController(workflowService, mock(WorkflowDefinitionService.class),
                new WorkflowNodeSchemaRegistry());

        ApiResponse<WorkflowRunPageResponse> response = controller.listRuns("wf-1", 2, RunStatus.SUCCEEDED, 1, 5);

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(page);
    }

    @Test
    void getsWorkflowRunDetail() {
        WorkflowService workflowService = mock(WorkflowService.class);
        WorkflowRunRecordResponse summary = new WorkflowRunRecordResponse("run-1", "wf-1", 2,
                Instant.parse("2026-06-24T04:00:00Z"), RunStatus.SUCCEEDED, "{\"answer\":\"ok\"}", null,
                Instant.parse("2026-06-24T04:00:03Z"));
        RunResponse run = new RunResponse("run-1", RunType.WORKFLOW, RunStatus.SUCCEEDED, "{\"input\":\"hi\"}",
                "{\"answer\":\"ok\"}", null, summary.startedAt(), summary.endedAt());
        RunStepResponse step = new RunStepResponse("step-1", "run-1", "llm_1", "{}", "{\"answer\":\"ok\"}", null,
                StepStatus.SUCCEEDED, Instant.parse("2026-06-24T04:00:01Z"),
                Instant.parse("2026-06-24T04:00:02Z"));
        WorkflowRunDetailResponse expected = new WorkflowRunDetailResponse(summary, run, List.of(step));
        when(workflowService.getRunDetail("run-1")).thenReturn(expected);
        WorkflowController controller = new WorkflowController(workflowService, mock(WorkflowDefinitionService.class),
                new WorkflowNodeSchemaRegistry());

        ApiResponse<WorkflowRunDetailResponse> response = controller.getRunDetail("run-1");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo(expected);
    }

    private WorkflowDefinition validDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
    }

}
