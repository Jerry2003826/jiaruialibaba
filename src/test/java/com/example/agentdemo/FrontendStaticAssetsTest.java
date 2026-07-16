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
                .andExpect(content().string(containsString("repair-workflow")))
                .andExpect(content().string(containsString("workflow-generator")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("智能体搭建助手", "修改当前画布", "AI 修复")
                        .doesNotContain(">创建一个客户评价自动分流系统"))
                .andExpect(content().string(containsString("insert-loop-template")))
                .andExpect(content().string(containsString("definition-history")))
                .andExpect(content().string(containsString("undo-workflow")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("撤回上一步"))
                .andExpect(content().string(containsString("route-map-panel")))
                .andExpect(content().string(containsString("route-map-list")))
                .andExpect(content().string(containsString("wf-issues")))
                .andExpect(content().string(containsString("wf-issues-pop")))
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
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("http-credentials-settings", "http-credential-form",
                                "http-credential-type", "http-credential-list", "HTTP 凭据中心"))
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
                .andExpect(content().string(containsString("/api/workflows/edit")))
                .andExpect(content().string(containsString("/api/workflows/edit/stream")))
                .andExpect(content().string(containsString("/api/workflows/repair")))
                .andExpect(content().string(containsString("/api/workflows/repair/stream")))
                .andExpect(content().string(containsString("/api/workflows/spec-drafts")))
                .andExpect(content().string(containsString("/api/workflows/prompt-drafts")))
                .andExpect(content().string(containsString("/api/workflows/governance/evaluate")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("error.code = payload?.code", "error.data = payload?.data",
                                "error.httpStatus = response.status"))
                .andExpect(content().string(containsString("/api/workflows/run")))
                .andExpect(content().string(containsString("/api/workflows/validate")))
                .andExpect(content().string(containsString("/api/settings/http-credentials")))
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
                .andExpect(content().string(containsString("outputMode")))
                .andExpect(content().string(containsString("outputSchema")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("输出结构约束", "复合条件模式", "复合条件列表"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("query: \"搜索内容\"", "topic: \"搜索类别\""))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("OPTION_LABELS", "全部满足", "任一满足", "等于", "包含", "文本"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("VARIABLE_PRESETS", "选择变量来源", "上一步输出", "节点输出"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("templateControlForField", "conditionListControl", "添加条件", "删除条件"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("conditionRuleControl", "condition-rule-card", "分支规则", "规则类型",
                                "单条件", "多条件", "左侧取值", "右侧取值", "高级配置", "原始条件 JSON",
                                "beforeChange?.()"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("hidePickerWhenLiteral", "右侧可以是固定值"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("DEFAULT_NODE_OUTPUT_FIELDS", "nodeOutputDescriptors", "outputSchemaFieldDescriptors",
                                "结构化输出", "完整输出"))
                // Variable graph: variable insertion should be driven by upstream node outputs and
                // outputSchema fields, not by users memorising raw {{nodes.nodeId.path}} strings.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("buildVariableGraph", "upstreamNodeIdsFor", "createVisualVariablePicker",
                                "renderVariablePickerPopover", "availableVariableDescriptors",
                                "inputVariableDescriptors", "collectTemplateVariablesFromValue",
                                "variable-picker-button", "variable-picker-popover",
                                "上游节点输出", "结构化结果", "已失效的变量引用"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("writeStateControl", "deriveStateKeyFromVariable", "添加状态字段",
                                "状态字段", "变量来源"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("nodeOutputOptionLabel", "schemaFieldLabel", "结构化 ·", "intent: \"意图\""))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("conditionBranchDescription", "templateShortLabel", "connectSourceBranch",
                                "EDGE_CONDITION_LABELS"))
                // Visual output-schema editor: fields defined on canvas drive the variable picker,
                // and user-provided titles beat the hardcoded label map.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("outputSchemaControl", "buildOutputSchema", "normalizeOutputSchema",
                                "添加字段", "显示名", "schema.title"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("schema-required-toggle", "schema-description-input",
                                "必填", "字段说明", "schema.required", "schema.description"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .doesNotContain("label: `节点输出 · ${nodeDisplayName(node)} · ${descriptor.label}`",
                                "结构化输出 · ${variableLabel(labelKey)}"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .doesNotContain("orderId: \"订单号\"", "customerName: \"客户姓名\"",
                                "trackingNumber: \"运单号\"", "latestEvent: \"最新动态\"",
                                "tool: [\"text\", \"found\", \"orderId\", \"status\", \"latestEvent\"]"))
                .andExpect(content().string(containsString("field.constraints?.allowedValues")))
                .andExpect(content().string(containsString("optionLabel(field.name, option)")))
                .andExpect(content().string(containsString("type === \"array\"")))
                .andExpect(content().string(containsString("parseJsonInput(value, [])")))
                .andExpect(content().string(containsString("FIELD_LABELS")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("http_request", "variable_aggregator", "httpCredentials",
                                "statusCode", "durationMs", "variableAggregatorOutputDescriptors",
                                "upstreamOnly", "expectedType"));
    }

    @Test
    void servesReportExportNodePanelAndArtifactResultActions() throws Exception {
        mockMvc.perform(get("/js/state.js"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("report_export", "报告导出", "artifacts", "printPreview"));

        mockMvc.perform(get("/js/workflow.js"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("renderReportExportSettingsPanel", "报告内容", "导出格式",
                                "business", "minimal", "academic", "retentionDays",
                                "upstreamOnly: true", "编辑报告格式"));

        mockMvc.perform(get("/js/runs.js"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("renderReportArtifacts", "下载", "打印", "requestArtifactBlob",
                                "URL.createObjectURL", "API.workflowRunDetail(run.runId)",
                                "API.workflowRunArtifacts(run.runId)", "hydrateArtifacts: false"));

        mockMvc.perform(get("/js/api.js"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("workflowRunArtifacts", "workflowRunDetail"));
    }

    @Test
    void servesSafeCustomNodePanelWithNamedInputsAndVisualOutputFields() throws Exception {
        mockMvc.perform(get("/js/state.js"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("custom", "自由节点", "inputs", "instruction", "template"));

        mockMvc.perform(get("/js/workflow.js"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("renderCustomSettingsPanel", "AI 处理", "模板转换", "命名输入",
                                "结构化输出字段", "upstreamOnly: true", "recordWorkflowUndo"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("不执行代码", "不直接访问网络", "HTTP 请求"));
    }

    @Test
    void servesWorkbenchUiModule() throws Exception {
        mockMvc.perform(get("/js/ui.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("loadHealth")))
                .andExpect(content().string(containsString("setActiveViewMode")))
                .andExpect(content().string(containsString("workflow-dify-mode")))
                .andExpect(content().string(containsString("renderRuntimeDetails")))
                .andExpect(content().string(containsString("workflowRuntime")))
                .andExpect(content().string(containsString("workflowRequirePublishedForRun")))
                .andExpect(content().string(containsString("undo-workflow")))
                .andExpect(content().string(containsString("repair-workflow")))
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
                .andExpect(content().string(containsString("recordWorkflowUndo")))
                .andExpect(content().string(containsString("captureWorkflowSnapshot")))
                .andExpect(content().string(containsString("applyWorkflowSnapshot")))
                .andExpect(content().string(containsString("undoLastWorkflowEdit")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("els.runWorkflow?.addEventListener(\"click\", openWorkflowRunPanel)",
                                "function openWorkflowRunPanel()", "focusFirstWorkflowInput")
                        .doesNotContain("els.runWorkflow?.addEventListener(\"click\", () => { openRunDrawer(); void runWorkflow(); })"))
                .andExpect(content().string(containsString("commitNodeConfig(node, fieldName, value")))
                .andExpect(content().string(containsString("conditionRuleControl(node.config, updatePreview, refreshPanel, () => recordWorkflowUndo")))
                .andExpect(content().string(containsString("insertLoopTemplate")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("publishDefinitionInFlight", "发布检查中", "已发布；画布有改动时请先保存"))
                .andExpect(content().string(containsString("generateWorkflowFromPrompt")))
                .andExpect(content().string(containsString("draftWorkflowSpecification")))
                .andExpect(content().string(containsString("renderGeneratorClarification")))
                .andExpect(content().string(containsString("appendClarificationOptionToPrompt")))
                .andExpect(content().string(containsString("renderGeneratorLockedSpec")))
                .andExpect(content().string(containsString("streamWorkflowGeneration")))
                .andExpect(content().string(containsString("repairWorkflowWithAi")))
                .andExpect(content().string(containsString("openWorkflowRepairPanel")))
                .andExpect(content().string(containsString("confirmWorkflowRepair")))
                .andExpect(content().string(containsString("data-confirm-ai-repair")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("els.repairWorkflow?.addEventListener(\"click\", () => openWorkflowRepairPanel())",
                                "描述你希望 AI 重点修复的问题")
                        .doesNotContain("els.repairWorkflow?.addEventListener(\"click\", () => void repairWorkflowWithAi())")
                        .doesNotContain("?.addEventListener(\"click\", () => void repairWorkflowWithAi(errorMessage))"))
                .andExpect(content().string(containsString("buildWorkflowRepairRequest")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("const prompt = `修复当前工作流", "用户补充问题")
                        .doesNotContain("const prompt = cleanText(els.generatorPrompt?.value)"))
                .andExpect(content().string(containsString("API.repairWorkflowStream")))
                .andExpect(content().string(containsString("renderGeneratorStreamFailure")))
                .andExpect(content().string(containsString("applyGeneratedWorkflow")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("isWorkflowResponseReady", "handleWorkflowAiOutcome",
                                "hasWorkflowCandidate", "applyWorkflow(response)",
                                "renderGeneratorGovernanceReport", "response?.status === \"READY\"",
                                "BLOCKED", "INFRA_ERROR", "已生成候选蓝图到画布", "待修复草稿")
                        .doesNotContain("if (!isWorkflowResponseReady(response)) return false;",
                                "已保留当前画布和需求描述")
                        .contains("workflowPhaseMessage", "eventName === \"phase\"",
                                "需求分析", "静态治理检查", "自动测试", "自动修复"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("governanceSummary", "governanceFindings", "governancePackVersions",
                                "activeRulePacks", "rulePacks", "repairAttempts", "testResults",
                                "attemptRunIds", "executedPath",
                                "assertions", "output", "测试输入", "证据", "运行路径", "runId",
                                "TAVILY_NOT_CONFIGURED", "请先在设置页配置 Tavily API Key",
                                "基础设施错误详情", "response?.notes",
                                "data-open-tavily-settings", "openTavilySettings", "去配置 Tavily"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("renderPublishGovernanceFailure", "WORKFLOW_GOVERNANCE_BLOCKED",
                                "WORKFLOW_GOVERNANCE_INFRA_ERROR", "发布前治理检查未通过",
                                "报告不可用", "error?.data")
                        .doesNotContain("refreshPublishGovernanceReport", "requestJson(API.governanceEvaluate"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("currentWorkflowLockedSpec", "lockedSpec: currentWorkflowLockedSpec",
                                "restoreWorkflowLockedSpec", "source.lockedSpec",
                                "specDraft?.spec", "streamWorkflowGeneration"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("已保留上方生成片段", "规格确认", "需要先确认几个边界", "按规格生成",
                                "generator-option-button", "补充说明", "可点击下面选项",
                                "业务领域", "必需能力", "输出对象"))
                .andExpect(content().string(containsString("saveCanvasPositions")))
                .andExpect(content().string(containsString("renderRouteMap")))
                .andExpect(content().string(containsString("route-highlight")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("显示名称", "所属流程", "技术 ID"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("满足", "不满足", "循环体", "退出循环"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("renderConditionNodeConfig", "node.type === \"condition\""))
                // Dify-like canvas cards: branch rows with per-branch connect ports.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("nodeBranches", "node-branch-row", "branch-port", "node-head", "node-in-dot"))
                // Dify-like inspector shell: node header + advanced settings fold.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("panel-node-head", "renderAdvancedNodeSettings", "高级设置", "inspector-section-title"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("renderWorkflowNameSettings", "workflow-name-card", "workflow-name-input",
                                "normalizeWorkflowName", "工作流名称", "保存时使用这个名称"))
                // Dify-like condition inspector: IF/ELSE are visible branch sections, with next-step cards inline.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("renderConditionBranchEditor", "renderConditionBranchNextSteps",
                                "condition-if-else-card", "IF 条件", "ELSE",
                                "用于定义当 IF 条件不满足时应执行的逻辑"))
                // Dify-like node registry: each business node owns a dedicated settings panel instead of
                // falling back to a single generic configFields form.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("NODE_PANEL_RENDERERS", "renderNodeSpecificSettings",
                                "renderLlmSettingsPanel", "renderToolSettingsPanel",
                                "renderRetrieverSettingsPanel", "renderLoopSettingsPanel",
                                "renderSubgraphSettingsPanel", "renderDynamicSettingsPanel",
                                "renderHttpRequestSettingsPanel", "renderVariableAggregatorSettingsPanel",
                                "renderGenericNodeSettings", "renderExecutionControls"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("模型与提示词", "工具调用", "检索设置", "循环设置",
                                "子工作流设置", "动态分配设置", "运行控制 / 状态写入"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("renderCurlImporter", "parseCurlRequest", "导入 cURL",
                                "loadHttpCredentialCatalog", "配置凭据",
                                "renderAggregatorCandidates", "添加分组", "动态输出预览",
                                "upstreamOnly: true", "expectedType"))
                // LLM prompt composition should be Dify-like: users choose a business input,
                // while the canvas writes the technical template reference into the prompt.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("renderLlmPromptBuilder", "inferLlmInputVariable", "composeLlmPrompt",
                                "extractLlmPromptParts", "generateLlmBusinessInstruction",
                                "applyPromptDraftConfiguration", "selectedVariablePickerLabel",
                                "node.config.outputMode", "node.config.outputSchema", "node.config.writeState",
                                "任务输入", "业务指令", "AI 生成完整文案",
                                "输入内容由系统自动接入", "无需手写 {{state.xxx}} 或 {{input.xxx}}"))
                // Automatically managed LLM router contracts should not expose the manual JSON controls.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("AUTO_STRUCTURED_HIDDEN_FIELDS", "isAutoStructuredOutputNode",
                                "renderAutoStructuredOutputNotice", "auto-structured-output-summary",
                                "结构化输出已自动配置")
                        .contains("options.autoStructured && AUTO_STRUCTURED_HIDDEN_FIELDS.has(field.name)"))
                // Dify-like build flow: "+" opens a block selector that auto-creates and connects.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("openBlockSelector", "addNextNode", "nextNodePosition",
                                "添加下一步", "连接到已有节点"))
                // Guided checklist: dangling nodes and missing branch targets are surfaced with jump-to.
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("workflowIssues", "renderIssues", "jumpToNode", "项待完善", "检查通过"))
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
                .andExpect(content().string(containsString("tokenUsage")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("applyAutomaticStructuredOutputContracts", "inferStructuredOutputProfile",
                                "CUSTOMER_SERVICE_INTENT_PROFILE", "autoStructuredOutputContract",
                                "isCustomerServiceIntentSchema")
                        .contains("config.outputMode = \"json\"",
                                "config.outputSchema = cloneStructuredValue(schema)",
                                "config.writeState = { ...profile.writeState, ...existingWriteState }",
                                "&& !isCustomerServiceIntentSchema(schema)"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("runWorkflowDefinition", "ensureWorkflowCanRun", "formatWorkflowValidationErrors",
                                "工作流未通过校验，不能运行"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("workflowRunPayload", "definitionId: state.definitionId",
                                "definitionVersion: state.definitionVersion",
                                "workflowDefinition: runWorkflowDefinition()"))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("effectiveWorkflowVariables", "discoverWorkflowInputVariables",
                                "setWorkflowRunVariables", "inputVariablePresentation",
                                "搜索主题", "输入要研究或搜索的主题"));
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
                .andExpect(result -> assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                        .contains("loadHttpCredentialsSettings", "createHttpCredential", "deleteHttpCredential",
                                "API.httpCredentials", "API.httpCredential"))
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
                .andExpect(content().string(containsString("workflowDefinitionVersion")))
                .andExpect(content().string(containsString("workflowDefinition: buildWorkflowDefinition()")))
                .andExpect(content().string(containsString("state.assistantWorkflowDefinitionStatus === \"PUBLISHED\"")))
                .andExpect(content().string(containsString("return workflowDefinitionReference(state.assistantWorkflowDefinitionId")))
                .andExpect(content().string(containsString("function workflowDefinitionReference(definitionId, version)")))
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
                .andExpect(content().string(containsString("Dify-like workflow editor")))
                .andExpect(content().string(containsString("body.workflow-dify-mode")))
                .andExpect(content().string(containsString("--dify-bg: #f0f2f7")))
                .andExpect(content().string(containsString("--dify-panel")))
                .andExpect(content().string(containsString("--dify-blue")))
                .andExpect(content().string(containsString(".node-branch-row")))
                .andExpect(content().string(containsString(".branch-tag")))
                .andExpect(content().string(containsString(".panel-node-head")))
                .andExpect(content().string(containsString(".workflow-name-card")))
                .andExpect(content().string(containsString(".workflow-name-input")))
                .andExpect(content().string(containsString(".inspector-advanced")))
                .andExpect(content().string(containsString(".block-selector")))
                .andExpect(content().string(containsString(".block-option")))
                .andExpect(content().string(containsString(".next-step-group")))
                .andExpect(content().string(containsString(".incoming-chip")))
                .andExpect(content().string(containsString(".issues-chip")))
                .andExpect(content().string(containsString(".issue-item")))
                .andExpect(content().string(containsString(".schema-editor")))
                .andExpect(content().string(containsString(".schema-row")))
                .andExpect(content().string(containsString(".llm-prompt-builder")))
                .andExpect(content().string(containsString(".llm-prompt-head")))
                .andExpect(content().string(containsString(".llm-prompt-draft")))
                .andExpect(content().string(containsString(".llm-business-prompt")))
                .andExpect(content().string(containsString(".schema-required-toggle")))
                .andExpect(content().string(containsString(".schema-description-input")))
                .andExpect(content().string(containsString("grid-template-areas:")))
                .andExpect(content().string(containsString(".workflow-dify-mode .wf-topbar")))
                .andExpect(content().string(containsString(".workflow-dify-mode .workflow-canvas")))
                .andExpect(content().string(containsString(".workflow-dify-mode .canvas-node")))
                .andExpect(content().string(containsString(".workflow-dify-mode .inspector-panel")))
                .andExpect(content().string(containsString(".workflow-dify-mode .palette")))
                .andExpect(content().string(containsString(".workflow-dify-mode .route-map-panel")))
                .andExpect(content().string(containsString(".wf-autosave")))
                .andExpect(content().string(containsString("@media (max-width: 640px)")))
                .andExpect(content().string(containsString(".wf-actions::-webkit-scrollbar")))
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
                .andExpect(content().string(containsString(".governance-report")))
                .andExpect(content().string(containsString(".governance-summary-grid")))
                .andExpect(content().string(containsString(".governance-status")))
                .andExpect(content().string(containsString(".governance-test-details")))
                .andExpect(content().string(containsString(".route-map-panel")))
                .andExpect(content().string(containsString(".route-filter")))
                .andExpect(content().string(containsString(".canvas-node.route-highlight")))
                .andExpect(content().string(containsString(".definition-history")))
                .andExpect(content().string(containsString(".order-editor-grid")))
                .andExpect(content().string(containsString(".history-list")))
                .andExpect(content().string(containsString(".template-control")))
                .andExpect(content().string(containsString(".condition-list-control")))
                .andExpect(content().string(containsString(".condition-row")))
                .andExpect(content().string(containsString(".condition-rule-card")))
                .andExpect(content().string(containsString(".condition-rule-grid")))
                .andExpect(content().string(containsString(".condition-if-else-card")))
                .andExpect(content().string(containsString(".condition-branch-section")))
                .andExpect(content().string(containsString(".condition-branch-next")))
                .andExpect(content().string(containsString(".condition-advanced-details")))
                .andExpect(content().string(containsString(".http-target-row")))
                .andExpect(content().string(containsString(".http-kv-row")))
                .andExpect(content().string(containsString(".http-curl-import")))
                .andExpect(content().string(containsString(".aggregator-group-card")))
                .andExpect(content().string(containsString(".aggregator-candidate-row")))
                .andExpect(content().string(containsString(".http-credential-settings-grid")))
                .andExpect(content().string(containsString("width: clamp(420px, 34vw, 520px)")))
                .andExpect(content().string(containsString(".condition-row-body")))
                .andExpect(content().string(containsString(".condition-cell")))
                .andExpect(content().string(containsString("grid-template-columns: minmax(0, 1fr) minmax(120px, .45fr)")));
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
