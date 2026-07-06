package com.example.agentdemo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private static final Pattern BLOCK_SCOPED_AGENT_WORKBENCH_DECLARATION =
            Pattern.compile("(^|\\n)\\s*(const|let)\\s+AgentWorkbench\\b");
    private static final List<String> WORKBENCH_SCRIPT_PATHS = List.of(
            "/js/api.js",
            "/js/state.js",
            "/js/ui.js",
            "/js/workflow.js",
            "/js/apps.js",
            "/js/knowledge.js",
            "/js/runs.js",
            "/js/tools.js",
            "/js/settings.js",
            "/js/main.js"
    );

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
                // Dify-like product surfaces: Apps (with API access) and Settings.
                .andExpect(content().string(containsString("data-view=\"apps\"")))
                .andExpect(content().string(containsString("view-apps")))
                .andExpect(content().string(containsString("apps-list")))
                .andExpect(content().string(containsString("create-app")))
                .andExpect(content().string(containsString("app-api-keys")))
                .andExpect(content().string(containsString("view-settings")))
                // Knowledge Base product view (new /api/knowledge-bases model).
                .andExpect(content().string(containsString("data-view=\"kb\"")))
                .andExpect(content().string(containsString("view-kb")))
                .andExpect(content().string(containsString("create-kb")))
                .andExpect(content().string(containsString("kb-doc-list")))
                .andExpect(content().string(containsString("/js/api.js")))
                .andExpect(content().string(containsString("/js/state.js")))
                .andExpect(content().string(containsString("/js/ui.js")))
                .andExpect(content().string(containsString("/js/workflow.js")))
                .andExpect(content().string(containsString("/js/apps.js")))
                .andExpect(content().string(containsString("/js/knowledge.js")))
                .andExpect(content().string(containsString("/js/runs.js")))
                .andExpect(content().string(containsString("/js/tools.js")))
                .andExpect(content().string(containsString("/js/settings.js")))
                .andExpect(content().string(containsString("/js/main.js")))
                .andExpect(content().string(not(containsString("/app.js"))))
                .andExpect(result -> assertScriptsAppearInOrder(result.getResponse().getContentAsString(StandardCharsets.UTF_8), List.of(
                        "<script src=\"/js/api.js\" defer></script>",
                        "<script src=\"/js/state.js\" defer></script>",
                        "<script src=\"/js/ui.js\" defer></script>",
                        "<script src=\"/js/workflow.js\" defer></script>",
                        "<script src=\"/js/apps.js\" defer></script>",
                        "<script src=\"/js/knowledge.js\" defer></script>",
                        "<script src=\"/js/runs.js\" defer></script>",
                        "<script src=\"/js/tools.js\" defer></script>",
                        "<script src=\"/js/settings.js\" defer></script>",
                        "<script src=\"/js/main.js\" defer></script>"
                )))
                .andExpect(content().string(containsString("/styles.css")));
    }

    @Test
    void classicWorkbenchScriptsAvoidRedeclaringAgentWorkbenchConst() throws Exception {
        for (String scriptPath : WORKBENCH_SCRIPT_PATHS) {
            assertClassicScriptNamespaceSafe(scriptPath);
        }
    }

    @Test
    void classicWorkbenchScriptsPassNodeSyntaxCheckWhenAvailable() throws Exception {
        Assumptions.assumeTrue(isNodeAvailable(), "node is not available for static JS syntax checks");
        for (String scriptPath : WORKBENCH_SCRIPT_PATHS) {
            mockMvc.perform(get(scriptPath))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertJavaScriptSyntaxValid(scriptPath,
                            result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void namespaceGuardRejectsAnyBlockScopedAgentWorkbenchDeclaration() {
        assertThatThrownBy(() -> assertNoBlockScopedAgentWorkbenchDeclaration(
                "\"use strict\";\nconst AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};\n"))
                .isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> assertNoBlockScopedAgentWorkbenchDeclaration(
                "\"use strict\";\nlet AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};\n"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void servesWorkbenchApiModule() throws Exception {
        mockMvc.perform(get("/js/api.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("window.AgentWorkbench = window.AgentWorkbench || {}")))
                .andExpect(content().string(containsString("AgentWorkbench.loadedModules = AgentWorkbench.loadedModules || []")))
                .andExpect(content().string(containsString("AgentWorkbench.loadedModules.push(\"api\")")))
                .andExpect(content().string(containsString("window.AgentWorkbench.API = API")))
                .andExpect(content().string(containsString("window.AgentWorkbench.requestJson = requestJson")))
                .andExpect(content().string(containsString("window.AgentWorkbench.consumeSse = consumeSse")))
                .andExpect(content().string(containsString("Invalid JSON response")))
                .andExpect(content().string(containsString("HTTP ${response.status}")))
                .andExpect(content().string(containsString("/api/workflows/generate")))
                .andExpect(content().string(containsString("/api/workflows/generate/stream")))
                .andExpect(content().string(containsString("/api/workflows/run")))
                .andExpect(content().string(containsString("/api/workflows/validate")))
                .andExpect(content().string(containsString("/api/agent/assistant-chat")))
                .andExpect(content().string(containsString("/api/chat/stream")));
    }

    @Test
    void servesWorkbenchStateModule() throws Exception {
        mockMvc.perform(get("/js/state.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ROUTE_RULES")))
                .andExpect(content().string(containsString("variableLabel")))
                .andExpect(content().string(containsString("CANVAS_POSITIONS_KEY_PREFIX")))
                .andExpect(content().string(containsString("AgentWorkbench.loadedModules.push(\"state\")")))
                .andExpect(content().string(containsString("window.AgentWorkbench.state = state")))
                .andExpect(content().string(containsString("window.AgentWorkbench.constants =")))
                .andExpect(content().string(containsString("window.AgentWorkbench.variableLabel = variableLabel")))
                .andExpect(content().string(containsString("FIELD_LABELS")));
    }

    @Test
    void servesWorkbenchUiModule() throws Exception {
        mockMvc.perform(get("/js/ui.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loadHealth")))
                .andExpect(content().string(containsString("renderRuntimeDetails")))
                .andExpect(content().string(containsString("workflowRuntime")))
                .andExpect(content().string(containsString("workflowRequirePublishedForRun")))
                .andExpect(content().string(containsString("AgentWorkbench.loadedModules.push(\"ui\")")))
                .andExpect(content().string(containsString("AgentWorkbench.cacheElements = cacheElements")))
                .andExpect(content().string(containsString("AgentWorkbench.runCommand = runCommand")));
    }

    @Test
    void servesWorkbenchWorkflowModule() throws Exception {
        mockMvc.perform(get("/js/workflow.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("class WorkflowCanvasController")))
                .andExpect(content().string(containsString("window.WorkflowCanvasController")))
                .andExpect(content().string(containsString("AgentWorkbench.controllers = AgentWorkbench.controllers || {}")))
                .andExpect(content().string(containsString("AgentWorkbench.workflowController = AgentWorkbench.controllers.workflow = new WorkflowCanvasController()")))
                .andExpect(content().string(containsString("window.WorkflowCanvasController = AgentWorkbench.workflowController")))
                .andExpect(content().string(containsString("window.AgentWorkbench.WorkflowCanvasController = WorkflowCanvasController")))
                .andExpect(content().string(containsString("window.AgentWorkbench.workflowController = window.WorkflowCanvasController")))
                .andExpect(content().string(containsString("buildWorkflowDefinition")))
                .andExpect(content().string(containsString("insertLoopTemplate")))
                .andExpect(content().string(containsString("generateWorkflowFromPrompt")))
                .andExpect(content().string(containsString("streamWorkflowGeneration")))
                .andExpect(content().string(containsString("applyGeneratedWorkflow")))
                .andExpect(content().string(containsString("saveCanvasPositions")))
                .andExpect(content().string(containsString("renderRouteMap")))
                .andExpect(content().string(containsString("route-highlight")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("显示名称", "所属流程", "技术 ID"))
                .andExpect(content().string(containsString("explicitRouteSummaries")))
                .andExpect(content().string(containsString("loadDefinitionHistory")));
    }

    @Test
    void servesWorkbenchAppsModule() throws Exception {
        mockMvc.perform(get("/js/apps.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loadApps")))
                .andExpect(content().string(containsString("API.apps")))
                .andExpect(content().string(containsString("createApiKey")))
                .andExpect(content().string(containsString("plaintextKey")))
                .andExpect(content().string(containsString("runSelectedApp")));
    }

    @Test
    void servesWorkbenchKnowledgeModule() throws Exception {
        mockMvc.perform(get("/js/knowledge.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loadKnowledgeBases")))
                .andExpect(content().string(containsString("API.knowledgeBases")))
                .andExpect(content().string(containsString("uploadKbFile")));
    }

    @Test
    void servesWorkbenchRunsModule() throws Exception {
        mockMvc.perform(get("/js/runs.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("animateRunOnCanvas")))
                .andExpect(content().string(containsString("API.workflowRunEvents(runId)")))
                .andExpect(content().string(containsString("applyRunEventToCanvas")))
                .andExpect(content().string(containsString("trace-driven highlighting")))
                .andExpect(content().string(containsString("runsPage?.content")))
                .andExpect(content().string(containsString("payload.label = cleanText(node.label)")))
                .andExpect(content().string(containsString("payload.route = cleanText(node.route)")))
                .andExpect(content().string(containsString("buildCompositeContainersFromSteps")))
                .andExpect(content().string(containsString("tokenUsageSummary")))
                .andExpect(content().string(containsString("tokenUsage")));
    }

    @Test
    void servesWorkbenchToolsModule() throws Exception {
        mockMvc.perform(get("/js/tools.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("bindTools")))
                .andExpect(content().string(containsString("loadTools")))
                .andExpect(content().string(containsString("API.tools")))
                .andExpect(content().string(containsString("API.mcpServers")))
                .andExpect(content().string(containsString("AgentWorkbench.loadedModules.push(\"tools\")")))
                .andExpect(content().string(containsString("AgentWorkbench.bindTools = bindTools")))
                .andExpect(content().string(containsString("AgentWorkbench.loadTools = loadTools")));
    }

    @Test
    void servesWorkbenchSettingsModule() throws Exception {
        mockMvc.perform(get("/js/settings.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("bindSettings")))
                .andExpect(content().string(containsString("loadSettings")))
                .andExpect(content().string(containsString("API.health")))
                .andExpect(content().string(containsString("API.mcpServers")))
                .andExpect(content().string(containsString("AgentWorkbench.loadedModules.push(\"settings\")")))
                .andExpect(content().string(containsString("AgentWorkbench.bindSettings = bindSettings")))
                .andExpect(content().string(containsString("AgentWorkbench.loadSettings = loadSettings")));
    }

    @Test
    void servesWorkbenchBootstrapModule() throws Exception {
        mockMvc.perform(get("/js/main.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("function bootstrapWorkbench()")))
                .andExpect(content().string(containsString("document.addEventListener(\"DOMContentLoaded\"")))
                .andExpect(content().string(containsString("AgentWorkbench.workflowController.init()")))
                .andExpect(content().string(containsString("AgentWorkbench.loadedModules.push(\"main\")")))
                .andExpect(content().string(containsString("window.AgentWorkbench.bootstrapWorkbench = bootstrapWorkbench")))
                .andExpect(content().string(containsString("window.AgentWorkbench.init = bootstrapWorkbench")))
                .andExpect(content().string(containsString("document.addEventListener(\"DOMContentLoaded\", AgentWorkbench.bootstrapWorkbench)")))
                .andExpect(content().string(containsString("workflowDefinitionId")))
                .andExpect(content().string(containsString("workflowDefinition: buildWorkflowDefinition()")))
                .andExpect(content().string(containsString("return { workflowDefinitionId: state.assistantWorkflowDefinitionId };")))
                .andExpect(content().string(containsString("bindAssistantWorkflowFromDefinition")))
                .andExpect(content().string(containsString("clearChatHistory")))
                .andExpect(content().string(containsString("consumeSse")))
                .andExpect(content().string(containsString("API.clearConversation(conversationId)")))
                .andExpect(content().string(containsString("editDocument")))
                .andExpect(content().string(containsString("resetDocumentEditor")))
                .andExpect(content().string(containsString("API.saveOrderEndpoint")))
                .andExpect(content().string(containsString("saveOrder")))
                .andExpect(content().string(containsString("editOrder")))
                .andExpect(content().string(containsString("resetOrderEditor")));
    }

    @Test
    void servesReplayHighlightingCopyOnWorkbenchHomePage() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("运行后节点状态回放", "事件回放式高亮", "trace-driven highlighting")
                        .doesNotContain("实时逐节点高亮"));
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

    private static void assertScriptsAppearInOrder(String html, List<String> scripts) {
        int previousIndex = -1;
        for (String script : scripts) {
            int currentIndex = html.indexOf(script);
            assertThat(currentIndex)
                    .withFailMessage("Expected script %s to exist in HTML", script)
                    .isGreaterThanOrEqualTo(0);
            assertThat(currentIndex)
                    .withFailMessage("Expected script order to keep %s after previous script", script)
                    .isGreaterThan(previousIndex);
            previousIndex = currentIndex;
        }
    }

    private void assertClassicScriptNamespaceSafe(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("window.AgentWorkbench = window.AgentWorkbench || {}")))
                .andExpect(result -> assertNoBlockScopedAgentWorkbenchDeclaration(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8)));
    }

    private static void assertNoBlockScopedAgentWorkbenchDeclaration(String scriptText) {
        assertThat(BLOCK_SCOPED_AGENT_WORKBENCH_DECLARATION.matcher(scriptText).find())
                .withFailMessage("Expected script to avoid block-scoped AgentWorkbench declarations, but found: %s", scriptText)
                .isFalse();
    }

    private static boolean isNodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version")
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void assertJavaScriptSyntaxValid(String path, String scriptText) throws Exception {
        Path tempFile = Files.createTempFile("frontend-static-", ".js");
        try {
            Files.writeString(tempFile, scriptText, StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("node", "--check", tempFile.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor())
                    .withFailMessage("Expected %s to pass node --check:%n%s", path, output)
                    .isZero();
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

}
