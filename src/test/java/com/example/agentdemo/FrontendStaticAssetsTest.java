package com.example.agentdemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:frontend_static_assets_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class FrontendStaticAssetsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void routesRootToWorkbenchHomePage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    void servesWorkbenchHomePage() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("app-shell")))
                .andExpect(content().string(containsString("view-chat")))
                .andExpect(content().string(containsString("data-chat-mode=\"assistant\"")))
                .andExpect(content().string(not(containsString("data-chat-mode=\"agent\""))))
                .andExpect(content().string(not(containsString("data-chat-mode=\"rag\""))))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("智能问答"))
                .andExpect(content().string(containsString("clear-chat")))
                .andExpect(content().string(containsString("runtime-details")))
                .andExpect(content().string(containsString("generate-workflow")))
                .andExpect(content().string(containsString("workflow-generator")))
                .andExpect(content().string(containsString("insert-loop-template")))
                .andExpect(content().string(containsString("definition-history")))
                .andExpect(content().string(containsString("route-map-panel")))
                .andExpect(content().string(containsString("route-map-list")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("路由分配"))
                .andExpect(content().string(containsString("reset-document-editor")))
                .andExpect(content().string(containsString("order-id")))
                .andExpect(content().string(containsString("order-list")))
                .andExpect(content().string(containsString("/app.js")))
                .andExpect(content().string(containsString("/styles.css")));
    }

    @Test
    void servesWorkbenchJavaScript() throws Exception {
        mockMvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("WorkflowCanvasController")))
                .andExpect(content().string(containsString("/api/workflows/generate")))
                .andExpect(content().string(containsString("/api/workflows/generate/stream")))
                .andExpect(content().string(containsString("/api/workflows/run")))
                .andExpect(content().string(containsString("/api/workflows/validate")))
                .andExpect(content().string(containsString("updateDocument:")))
                .andExpect(content().string(containsString("editDocument")))
                .andExpect(content().string(containsString("resetDocumentEditor")))
                .andExpect(content().string(containsString("listOrders:")))
                .andExpect(content().string(containsString("saveOrder")))
                .andExpect(content().string(containsString("editOrder")))
                .andExpect(content().string(containsString("resetOrderEditor")))
                .andExpect(content().string(containsString("/api/agent/assistant-chat")))
                .andExpect(content().string(containsString("workflowDefinitionId")))
                .andExpect(content().string(containsString("workflowDefinition: buildWorkflowDefinition()")))
                .andExpect(content().string(containsString("return { workflowDefinitionId: state.definitionId };")))
                .andExpect(content().string(containsString("return { workflowDefinitionId: state.assistantWorkflowDefinitionId };")))
                .andExpect(content().string(containsString("bindAssistantWorkflowFromDefinition")))
                .andExpect(content().string(containsString("/api/chat/conversations/")))
                .andExpect(content().string(containsString("/api/chat/stream")))
                .andExpect(content().string(containsString("clearChatHistory")))
                .andExpect(content().string(containsString("consumeSse")))
                .andExpect(content().string(containsString("workflowRuntime")))
                .andExpect(content().string(containsString("workflowRequirePublishedForRun")))
                .andExpect(content().string(containsString("insertLoopTemplate")))
                .andExpect(content().string(containsString("generateWorkflowFromPrompt")))
                .andExpect(content().string(containsString("streamWorkflowGeneration")))
                .andExpect(content().string(containsString("applyGeneratedWorkflow")))
                .andExpect(content().string(containsString("saveCanvasPositions")))
                .andExpect(content().string(containsString("ROUTE_RULES")))
                .andExpect(content().string(containsString("renderRouteMap")))
                .andExpect(content().string(containsString("route-highlight")))
                .andExpect(content().string(containsString("variableLabel")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("显示名称", "所属流程", "技术 ID"))
                .andExpect(content().string(containsString("payload.label = cleanText(node.label)")))
                .andExpect(content().string(containsString("payload.route = cleanText(node.route)")))
                .andExpect(content().string(containsString("explicitRouteSummaries")))
                .andExpect(content().string(containsString("buildCompositeContainersFromSteps")))
                .andExpect(content().string(containsString("tokenUsageSummary")))
                .andExpect(content().string(containsString("tokenUsage")))
                .andExpect(content().string(containsString("loadDefinitionHistory")))
                .andExpect(content().string(containsString("runsPage?.content")));
    }

    @Test
    void servesWorkbenchStylesheet() throws Exception {
        mockMvc.perform(get("/styles.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".workflow-canvas")))
                .andExpect(content().string(containsString(".canvas-node")))
                .andExpect(content().string(containsString(".inspector-panel")))
                .andExpect(content().string(containsString("overflow-x: hidden")))
                .andExpect(content().string(containsString("grid-template-columns: minmax(0, 1fr) 18px minmax(0, 1fr)")))
                .andExpect(content().string(containsString("grid-template-areas:")))
                .andExpect(content().string(containsString("min-width: 480px")))
                .andExpect(content().string(containsString("span:not(.palette-ico):not(.plus)")))
                .andExpect(content().string(containsString(".workflow-generator")))
                .andExpect(content().string(containsString(".generator-stream-output")))
                .andExpect(content().string(containsString(".route-map-panel")))
                .andExpect(content().string(containsString(".route-filter")))
                .andExpect(content().string(containsString(".canvas-node.route-highlight")))
                .andExpect(content().string(containsString(".definition-history")))
                .andExpect(content().string(containsString(".order-editor-grid")))
                .andExpect(content().string(containsString(".history-list")));
    }

}
