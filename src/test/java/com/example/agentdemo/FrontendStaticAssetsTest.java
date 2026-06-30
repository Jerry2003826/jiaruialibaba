package com.example.agentdemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
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
                .andExpect(content().string(containsString("runtime-details")))
                .andExpect(content().string(containsString("insert-loop-template")))
                .andExpect(content().string(containsString("definition-history")))
                .andExpect(content().string(containsString("/app.js")))
                .andExpect(content().string(containsString("/styles.css")));
    }

    @Test
    void servesWorkbenchJavaScript() throws Exception {
        mockMvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("WorkflowCanvasController")))
                .andExpect(content().string(containsString("/api/workflows/run")))
                .andExpect(content().string(containsString("/api/workflows/validate")))
                .andExpect(content().string(containsString("/api/rag/chat")))
                .andExpect(content().string(containsString("/api/agent/tool-chat")))
                .andExpect(content().string(containsString("/api/chat/stream")))
                .andExpect(content().string(containsString("consumeSse")))
                .andExpect(content().string(containsString("workflowRuntime")))
                .andExpect(content().string(containsString("workflowRequirePublishedForRun")))
                .andExpect(content().string(containsString("insertLoopTemplate")))
                .andExpect(content().string(containsString("saveCanvasPositions")))
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
                .andExpect(content().string(containsString(".definition-history")))
                .andExpect(content().string(containsString(".history-list")));
    }

}
