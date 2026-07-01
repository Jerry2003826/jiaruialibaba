package com.example.agentdemo.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), previewService);

        mockMvc.perform(post("/api/workflows/preview-graph")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(previewService);
    }

    @Test
    void runGraphRouteReturnsJsonResponse() throws Exception {
        WorkflowRunGraphService workflowRunGraphService = mock(WorkflowRunGraphService.class);
        WorkflowRunGraphResponse expected = new WorkflowRunGraphResponse("run-1", "wf-1", 2, null,
                new WorkflowValidationSummary(2, 1, true, "start", "end", List.of("start", "end")),
                List.of(
                        new WorkflowRunGraphNodeView("start", "start", "start (start) SUCCEEDED", true, null,
                                "step-start", null),
                        new WorkflowRunGraphNodeView("end", "end", "end (end) NOT_EXECUTED", false, null,
                                null, null)),
                List.of(new WorkflowRunGraphEdgeView("start", "end", null, null, false)),
                "flowchart TD\n  n0[\"start (start) SUCCEEDED\"]");
        when(workflowRunGraphService.getRunGraph("run-1")).thenReturn(expected);
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                workflowRunGraphService);

        mockMvc.perform(get("/api/workflows/runs/run-1/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.definitionId").value("wf-1"))
                .andExpect(jsonPath("$.data.nodes[0].executed").value(true))
                .andExpect(jsonPath("$.data.edges[0].traversed").value(false));

        verify(workflowRunGraphService).getRunGraph("run-1");
    }

    @Test
    void generateRouteReturnsWorkflowDefinition() throws Exception {
        WorkflowGenerationService generationService = mock(WorkflowGenerationService.class);
        WorkflowGenerationResponse expected = new WorkflowGenerationResponse("知识库问答工作流", "generated",
                new WorkflowDefinition(
                        List.of(
                                new WorkflowNode("start", "start", Map.of()),
                                new WorkflowNode("retriever_1", "retriever", Map.of("topK", 3)),
                                new WorkflowNode("llm_1", "llm", Map.of("prompt", "Context: {{context}}")),
                                new WorkflowNode("end", "end", Map.of())),
                        List.of(
                                new WorkflowEdge("start", "retriever_1"),
                                new WorkflowEdge("retriever_1", "llm_1"),
                                new WorkflowEdge("llm_1", "end"))),
                List.of("ok"));
        when(generationService.generate(any(WorkflowGenerationRequest.class))).thenReturn(expected);
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), generationService);

        mockMvc.perform(post("/api/workflows/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("prompt", "先检索知识库再回答"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("知识库问答工作流"))
                .andExpect(jsonPath("$.data.workflowDefinition.nodes[1].type").value("retriever"))
                .andExpect(jsonPath("$.data.workflowDefinition.edges[0].to").value("retriever_1"));

        verify(generationService).generate(any(WorkflowGenerationRequest.class));
    }

    @Test
    void generateRouteAcceptsLongPromptDescription() throws Exception {
        WorkflowGenerationService generationService = mock(WorkflowGenerationService.class);
        WorkflowGenerationResponse expected = new WorkflowGenerationResponse("长提示词工作流", "generated",
                new WorkflowDefinition(
                        List.of(
                                new WorkflowNode("start", "start", Map.of()),
                                new WorkflowNode("llm_1", "llm", Map.of("prompt", "Answer {{input}}")),
                                new WorkflowNode("end", "end", Map.of())),
                        List.of(
                                new WorkflowEdge("start", "llm_1"),
                                new WorkflowEdge("llm_1", "end"))),
                List.of("ok"));
        when(generationService.generate(any(WorkflowGenerationRequest.class))).thenReturn(expected);
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), generationService);
        String longPrompt = "请根据这个很长的描述生成知识库问答工作流。" + "补充上下文".repeat(260);

        mockMvc.perform(post("/api/workflows/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("prompt", longPrompt))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.workflowDefinition.nodes[1].type").value("llm"));

        verify(generationService).generate(argThat(request -> request.prompt().equals(longPrompt)));
    }

    @Test
    void generateRouteRejectsPromptOverRequestLimit() throws Exception {
        WorkflowGenerationService generationService = mock(WorkflowGenerationService.class);
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), generationService);

        mockMvc.perform(post("/api/workflows/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("prompt", "x".repeat(4001)))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(generationService);
    }

    private MockMvc mockMvc(WorkflowGraphPreviewService previewService) {
        return mockMvc(mock(WorkflowService.class), previewService, mock(WorkflowRunGraphService.class));
    }

    private MockMvc mockMvc(WorkflowService workflowService, WorkflowGraphPreviewService previewService) {
        return mockMvc(workflowService, previewService, mock(WorkflowRunGraphService.class));
    }

    private MockMvc mockMvc(WorkflowService workflowService, WorkflowGraphPreviewService previewService,
            WorkflowRunGraphService workflowRunGraphService) {
        return mockMvc(workflowService, previewService, workflowRunGraphService, new WorkflowGenerationService());
    }

    private MockMvc mockMvc(WorkflowService workflowService, WorkflowGraphPreviewService previewService,
            WorkflowRunGraphService workflowRunGraphService, WorkflowGenerationService generationService) {
        WorkflowController controller = new WorkflowController(workflowService, mock(WorkflowDefinitionService.class),
                new WorkflowNodeSchemaRegistry(), previewService, workflowRunGraphService, generationService);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

}
