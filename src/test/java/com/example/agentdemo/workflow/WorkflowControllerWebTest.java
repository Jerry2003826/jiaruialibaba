package com.example.agentdemo.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkflowControllerWebTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void previewGraphRouteAcceptsJsonRequest() throws Exception {
        WorkflowGraphPreviewService previewService = mock(WorkflowGraphPreviewService.class);
        WorkflowGraphPreviewResponse expected = WorkflowGraphPreviewResponse.valid(
                new WorkflowValidationSummary(2, 1, true, "start", "end", List.of("start", "end")),
                List.of(
                        new WorkflowGraphNodeView("start", "start", "start (start)"),
                        new WorkflowGraphNodeView("end", "end", "end (end)")),
                List.of(new WorkflowGraphEdgeView("start", "end", null, null)),
                "flowchart TD\n  n0[\"start (start)\"]\n  n1[\"end (end)\"]\n  n0 --> n1");
        when(previewService.preview(any(WorkflowGraphPreviewRequest.class))).thenReturn(expected);
        MockMvc mockMvc = mockMvc(previewService);

        mockMvc.perform(post("/api/workflows/preview-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workflowDefinition", Map.of(
                                        "nodes", List.of(
                                                Map.of("id", "start", "type", "start", "config", Map.of()),
                                                Map.of("id", "end", "type", "end", "config", Map.of())),
                                        "edges", List.of(Map.of("from", "start", "to", "end")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.nodes[0].id").value("start"))
                .andExpect(jsonPath("$.data.edges[0].from").value("start"));

        verify(previewService).preview(any(WorkflowGraphPreviewRequest.class));
    }

    @Test
    void previewGraphRouteRejectsMissingDefinition() throws Exception {
        WorkflowGraphPreviewService previewService = mock(WorkflowGraphPreviewService.class);
        MockMvc mockMvc = mockMvc(previewService);

        mockMvc.perform(post("/api/workflows/preview-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(previewService);
    }

    private MockMvc mockMvc(WorkflowGraphPreviewService previewService) {
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class),
                mock(WorkflowDefinitionService.class), new WorkflowNodeSchemaRegistry(), previewService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

}
