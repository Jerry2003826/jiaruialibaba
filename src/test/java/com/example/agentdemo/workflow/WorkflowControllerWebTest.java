package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessDataException;
import com.example.agentdemo.common.GlobalExceptionHandler;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationReport;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
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
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prompt", "先检索知识库再回答",
                                "lockedSpec", Map.of("domain", "knowledge-base", "goal", "answer docs")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("知识库问答工作流"))
                .andExpect(jsonPath("$.data.workflowDefinition.nodes[1].type").value("retriever"))
                .andExpect(jsonPath("$.data.workflowDefinition.edges[0].to").value("retriever_1"));

        verify(generationService).generate(argThat(request -> request.lockedSpec().isObject()
                && "answer docs".equals(request.lockedSpec().path("goal").asText())));
    }

    @Test
    void governanceEvaluateAcceptsObjectLockedSpecAndReturnsStructuredOutcome() throws Exception {
        WorkflowGenerationService generationService = mock(WorkflowGenerationService.class);
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
        WorkflowGovernanceEvaluationResponse expected = new WorkflowGovernanceEvaluationResponse(
                WorkflowGenerationStatus.READY,
                definition,
                new WorkflowGovernanceReport(List.of()),
                new WorkflowEvaluationReport(Map.of("message", "test"), List.of()),
                List.of(),
                List.of(new WorkflowActiveRulePack("core", "1.0.0")));
        when(generationService.evaluateGovernance(any(WorkflowGovernanceEvaluationRequest.class)))
                .thenReturn(expected);
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), generationService);

        mockMvc.perform(post("/api/workflows/governance/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workflowDefinition", definition,
                                "lockedSpec", Map.of(
                                        "domain", "customer-service-ecommerce",
                                        "goal", "route customer reviews"),
                                "supplementalInput", Map.of("message", "test")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.governanceReport.findings").isArray())
                .andExpect(jsonPath("$.data.evaluationReport.supplementalInput.message").value("test"))
                .andExpect(jsonPath("$.data.testResults").isArray())
                .andExpect(jsonPath("$.data.activeRulePacks[0].id").value("core"))
                .andExpect(jsonPath("$.data.activeRulePacks[0].version").value("1.0.0"));

        verify(generationService).evaluateGovernance(argThat(request -> request.lockedSpec().isObject()
                && "customer-service-ecommerce".equals(request.lockedSpec().path("domain").asText())));
    }

    @Test
    void governanceEvaluateRejectsJsonNullSupplementalValuesWithStableValidationError() throws Exception {
        WorkflowGenerationService generationService = mock(WorkflowGenerationService.class);
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), generationService);

        mockMvc.perform(post("/api/workflows/governance/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workflowDefinition": {
                                    "nodes": [
                                      {"id":"start","type":"start","config":{}},
                                      {"id":"end","type":"end","config":{}}
                                    ],
                                    "edges": [{"from":"start","to":"end"}]
                                  },
                                  "supplementalInput": {"message": null}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(generationService);
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

    @Test
    void editRouteReturnsEditedWorkflowDefinition() throws Exception {
        WorkflowGenerationService generationService = mock(WorkflowGenerationService.class);
        WorkflowGenerationResponse expected = new WorkflowGenerationResponse("客户评价系统", "edited",
                new WorkflowDefinition(
                        List.of(
                                new WorkflowNode("start", "start", Map.of()),
                                new WorkflowNode("llm_positive", "llm", Map.of("prompt", "营销 {{input.message}}")),
                                new WorkflowNode("llm_shipping", "llm", Map.of("prompt", "运输 {{input.message}}")),
                                new WorkflowNode("end", "end", Map.of())),
                        List.of(
                                new WorkflowEdge("start", "llm_positive"),
                                new WorkflowEdge("llm_positive", "llm_shipping"),
                                new WorkflowEdge("llm_shipping", "end"))),
                List.of("edited"));
        when(generationService.edit(any(WorkflowEditRequest.class))).thenReturn(expected);
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), generationService);

        mockMvc.perform(post("/api/workflows/edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prompt", "增加运输问题处理",
                                "name", "客户评价系统",
                                "description", "当前版本",
                                "workflowDefinition", Map.of(
                                        "nodes", List.of(
                                                Map.of("id", "start", "type", "start", "config", Map.of()),
                                                Map.of("id", "llm_positive", "type", "llm", "config",
                                                        Map.of("prompt", "营销 {{input.message}}")),
                                                Map.of("id", "end", "type", "end", "config", Map.of())),
                                        "edges", List.of(
                                                Map.of("from", "start", "to", "llm_positive"),
                                                Map.of("from", "llm_positive", "to", "end")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("客户评价系统"))
                .andExpect(jsonPath("$.data.workflowDefinition.nodes[2].id").value("llm_shipping"));

        verify(generationService).edit(argThat(request ->
                request.prompt().equals("增加运输问题处理")
                        && request.workflowDefinition().nodes().stream()
                                .anyMatch(node -> node.id().equals("llm_positive"))));
    }

    @Test
    void repairRouteReturnsRepairedWorkflowDefinition() throws Exception {
        WorkflowGenerationService generationService = mock(WorkflowGenerationService.class);
        WorkflowGenerationResponse expected = new WorkflowGenerationResponse("客户评价系统", "repaired",
                new WorkflowDefinition(
                        List.of(
                                new WorkflowNode("start", "start", Map.of()),
                                new WorkflowNode("llm_sentiment", "llm",
                                        Map.of("prompt", "判断评价：{{input.message}}")),
                                new WorkflowNode("end", "end", Map.of())),
                        List.of(
                                new WorkflowEdge("start", "llm_sentiment"),
                                new WorkflowEdge("llm_sentiment", "end"))),
                List.of("已按错误信息修复"));
        when(generationService.repair(any(WorkflowRepairRequest.class))).thenReturn(expected);
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), generationService);

        mockMvc.perform(post("/api/workflows/repair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "prompt", "创建客户评价分流",
                                "error", "节点 cond_sentiment 使用了不支持的模板变量 {{state.sentiment}}",
                                "name", "客户评价系统",
                                "description", "当前画布",
                                "workflowDefinition", Map.of(
                                        "nodes", List.of(
                                                Map.of("id", "start", "type", "start", "config", Map.of()),
                                                Map.of("id", "cond_sentiment", "type", "condition", "config",
                                                        Map.of("left", "{{state.sentiment}}", "operator", "equals",
                                                                "right", "positive")),
                                                Map.of("id", "end", "type", "end", "config", Map.of())),
                                        "edges", List.of(
                                                Map.of("from", "start", "to", "cond_sentiment"),
                                                Map.of("from", "cond_sentiment", "to", "end",
                                                        "condition", "true")))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("客户评价系统"))
                .andExpect(jsonPath("$.data.workflowDefinition.nodes[1].id").value("llm_sentiment"));

        verify(generationService).repair(argThat(request ->
                request.prompt().equals("创建客户评价分流")
                        && request.error().contains("state.sentiment")
                        && request.workflowDefinition().nodes().stream()
                                .anyMatch(node -> node.id().equals("cond_sentiment"))));
    }

    @Test
    void promptDraftRouteReturnsGeneratedInstruction() throws Exception {
        WorkflowPromptDraftService promptDraftService = mock(WorkflowPromptDraftService.class);
        when(promptDraftService.draft(any(WorkflowPromptDraftRequest.class)))
                .thenReturn(new WorkflowPromptDraftResponse("你是客户评价分类助手。请只输出 JSON。"));
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), new WorkflowGenerationService(), promptDraftService);

        mockMvc.perform(post("/api/workflows/prompt-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requirement", "判断客户评论正负面",
                                "nodeLabel", "评价分类",
                                "inputLabel", "输入内容 · 用户消息"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.instruction").value("你是客户评价分类助手。请只输出 JSON。"));

        verify(promptDraftService).draft(argThat(request ->
                request.requirement().equals("判断客户评论正负面")
                        && request.nodeLabel().equals("评价分类")));
    }

    @Test
    void promptDraftRouteRejectsBlankRequirement() throws Exception {
        WorkflowPromptDraftService promptDraftService = mock(WorkflowPromptDraftService.class);
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), new WorkflowGenerationService(), promptDraftService);

        mockMvc.perform(post("/api/workflows/prompt-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("requirement", ""))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(promptDraftService);
    }

    @Test
    void specDraftRouteReturnsClarificationQuestions() throws Exception {
        WorkflowSpecDraftService specDraftService = mock(WorkflowSpecDraftService.class);
        when(specDraftService.draft(any(WorkflowSpecDraftRequest.class)))
                .thenReturn(new WorkflowSpecDraftResponse(
                        WorkflowSpecDraftResponse.Status.NEEDS_CLARIFICATION,
                        "客户评价自动分流",
                        List.of("是否需要真实调用部门系统？"),
                        List.of(new WorkflowSpecDraftResponse.Clarification(
                                "是否需要真实调用部门系统？",
                                List.of("只生成内部处理文案", "调用现有系统接口", "先人工确认再处理"),
                                "可补充部门系统名称")),
                        Map.of("goal", "客户评价分流"),
                        ""));
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), mock(WorkflowGraphPreviewService.class),
                mock(WorkflowRunGraphService.class), new WorkflowGenerationService(),
                new WorkflowPromptDraftService(), specDraftService);

        mockMvc.perform(post("/api/workflows/spec-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("prompt", "创建客户评价系统"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("NEEDS_CLARIFICATION"))
                .andExpect(jsonPath("$.data.summary").value("客户评价自动分流"))
                .andExpect(jsonPath("$.data.questions[0]").value("是否需要真实调用部门系统？"))
                .andExpect(jsonPath("$.data.clarifications[0].options[0]").value("只生成内部处理文案"))
                .andExpect(jsonPath("$.data.clarifications[0].freeformPrompt").value("可补充部门系统名称"))
                .andExpect(jsonPath("$.data.spec.goal").value("客户评价分流"));

        verify(specDraftService).draft(argThat(request -> request.prompt().equals("创建客户评价系统")));
    }

    @Test
    void saveDefinitionRouteAcceptsIncompleteDraftWithEmptyEdges() throws Exception {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinition draft = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of());
        when(definitionService.save(any(WorkflowDefinitionSaveRequest.class)))
                .thenReturn(new WorkflowDefinitionResponse("wf-draft", "客户评价系统", null, draft, 1,
                        WorkflowDefinitionStatus.DRAFT, Instant.now(), Instant.now()));
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), definitionService,
                mock(WorkflowGraphPreviewService.class), mock(WorkflowRunGraphService.class),
                new WorkflowGenerationService(), new WorkflowPromptDraftService());

        mockMvc.perform(post("/api/workflows/definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "客户评价系统",
                                "lockedSpec", Map.of(
                                        "domain", "customer-service-ecommerce",
                                        "goal", "客户评价分流"),
                                "workflowDefinition", Map.of(
                                        "nodes", List.of(
                                                Map.of("id", "start", "type", "start", "config", Map.of()),
                                                Map.of("id", "end", "type", "end", "config", Map.of())),
                                        "edges", List.of())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.workflowDefinition.edges").isEmpty());

        verify(definitionService).save(argThat(request -> request.workflowDefinition().edges().isEmpty()
                && "客户评价分流".equals(request.lockedSpec().path("goal").asText())));
    }

    @Test
    void publishFailureReturnsTheExactGovernanceSnapshotWithoutReevaluation() throws Exception {
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        WorkflowDefinition savedDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("saved_llm", "llm", Map.of("prompt", "saved {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "saved_llm"), new WorkflowEdge("saved_llm", "end")));
        WorkflowGovernanceEvaluationResponse snapshot = new WorkflowGovernanceEvaluationResponse(
                WorkflowGenerationStatus.BLOCKED,
                savedDefinition,
                new WorkflowGovernanceReport(List.of()),
                new WorkflowEvaluationReport(Map.of("message", "saved-version-case"), List.of()),
                List.of(),
                List.of(new WorkflowActiveRulePack("core", "1.0.0")));
        doThrow(new BusinessDataException("WORKFLOW_GOVERNANCE_BLOCKED", "blocked", snapshot))
                .when(definitionService).publish("wf-saved");
        MockMvc mockMvc = mockMvc(mock(WorkflowService.class), definitionService,
                mock(WorkflowGraphPreviewService.class), mock(WorkflowRunGraphService.class),
                new WorkflowGenerationService(), new WorkflowPromptDraftService());

        mockMvc.perform(post("/api/workflows/definitions/wf-saved/publish"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("WORKFLOW_GOVERNANCE_BLOCKED"))
                .andExpect(jsonPath("$.data.status").value("BLOCKED"))
                .andExpect(jsonPath("$.data.workflowDefinition.nodes[1].id").value("saved_llm"))
                .andExpect(jsonPath("$.data.evaluationReport.supplementalInput.message")
                        .value("saved-version-case"));

        verify(definitionService).publish("wf-saved");
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
        return mockMvc(workflowService, previewService, workflowRunGraphService, generationService,
                new WorkflowPromptDraftService());
    }

    private MockMvc mockMvc(WorkflowService workflowService, WorkflowGraphPreviewService previewService,
            WorkflowRunGraphService workflowRunGraphService, WorkflowGenerationService generationService,
            WorkflowPromptDraftService promptDraftService) {
        return mockMvc(workflowService, previewService, workflowRunGraphService, generationService,
                promptDraftService, new WorkflowSpecDraftService());
    }

    private MockMvc mockMvc(WorkflowService workflowService, WorkflowGraphPreviewService previewService,
            WorkflowRunGraphService workflowRunGraphService, WorkflowGenerationService generationService,
            WorkflowPromptDraftService promptDraftService, WorkflowSpecDraftService specDraftService) {
        return mockMvc(workflowService, mock(WorkflowDefinitionService.class), previewService, workflowRunGraphService,
                generationService, promptDraftService, specDraftService);
    }

    private MockMvc mockMvc(WorkflowService workflowService, WorkflowDefinitionService definitionService,
            WorkflowGraphPreviewService previewService, WorkflowRunGraphService workflowRunGraphService,
            WorkflowGenerationService generationService, WorkflowPromptDraftService promptDraftService) {
        return mockMvc(workflowService, definitionService, previewService, workflowRunGraphService, generationService,
                promptDraftService, new WorkflowSpecDraftService());
    }

    private MockMvc mockMvc(WorkflowService workflowService, WorkflowDefinitionService definitionService,
            WorkflowGraphPreviewService previewService, WorkflowRunGraphService workflowRunGraphService,
            WorkflowGenerationService generationService, WorkflowPromptDraftService promptDraftService,
            WorkflowSpecDraftService specDraftService) {
        WorkflowController controller = new WorkflowController(workflowService, definitionService,
                new WorkflowNodeSchemaRegistry(), previewService, workflowRunGraphService, generationService,
                promptDraftService, specDraftService);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

}
