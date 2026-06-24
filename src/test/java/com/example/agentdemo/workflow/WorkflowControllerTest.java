package com.example.agentdemo.workflow;

import com.example.agentdemo.common.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
                .containsExactly("start", "retriever", "llm", "tool", "end");
    }

    @Test
    void savesWorkflowDefinition() {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinitionResponse expected = new WorkflowDefinitionResponse("wf-1", "Support Bot", null,
                definition, null, null);
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
                validDefinition(), null, null);
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
                validDefinition(), null, null);
        when(definitionService.get("wf-1")).thenReturn(expected);
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class), definitionService,
                new WorkflowNodeSchemaRegistry());

        ApiResponse<WorkflowDefinitionResponse> response = controller.getDefinition("wf-1");

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
