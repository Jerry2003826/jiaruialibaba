package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.workflow.governance.WorkflowBuilderContext;
import com.example.agentdemo.workflow.governance.WorkflowBuilderContextService;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseResult;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseStatus;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationService;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceFinding;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceReport;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceService;
import com.example.agentdemo.workflow.governance.WorkflowRuleCatalog;
import com.example.agentdemo.workflow.http.HttpCredentialResponse;
import com.example.agentdemo.workflow.http.HttpCredentialService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@Service
public class WorkflowGenerationService {

    private static final int MAX_GENERATION_ATTEMPTS = 3;

    private static final String SUPERPOWERS_REPAIR_INSTRUCTIONS = """
            Superpowers 修复流程（强制执行，不要绕过流程）：
            1. Diagnose：先根据错误、当前工作流 JSON、节点连线和模板变量定位根因，判断是变量、结构化输出、分支拓扑、最终输出或业务语义问题。
            2. Plan：只制定最小修复计划，说明要保留哪些节点、要改哪些 config/prompt/outputSchema/writeState/edge。
            3. Implement：按计划输出完整修复后的 workflowDefinition，禁止输出片段、禁止发明节点类型、禁止丢掉用户已配置的业务意图。
            4. Verify：必须提供 testInput，并让修复后的流程在语义上能跑通到 end；如果修复裸 JSON 问题，最终节点必须产生客户可读自然语言。
            - notes 必须包含四类证据：诊断结论、修复计划、实际改动、验证样例。
            - 如果没有按 Diagnose -> Plan -> Implement -> Verify 思考，不要输出结果。
            """;

    private static final String SYSTEM_PROMPT = """
            你是平台内置的“智能体搭建助手”和工作流编排器。你的任务不是回答业务问题，而是把用户的自然语言需求
            转换成当前平台可以直接运行的智能体工作流。
            只输出 JSON，不要输出 Markdown、解释、代码块或额外文本。

            JSON 结构必须是：
            {
              "name": "简短中文名称",
              "description": "一句话描述",
              "testInput": {"message": "一条真实用户输入，用来自动试运行这个工作流"},
              "workflowDefinition": {
                "nodes": [
                  {"id":"start","type":"start","label":"开始入口","config":{}},
                  {"id":"llm_1","type":"llm","label":"回答生成","route":"默认流程","config":{"prompt":"..."}},
                  {"id":"end","type":"end","label":"结束输出","config":{}}
                ],
                "edges": [
                  {"from":"start","to":"llm_1","label":"进入回答","route":"默认流程"},
                  {"from":"llm_1","to":"end","label":"输出结果","route":"默认流程"}
                ]
              },
              "notes": ["一句中文说明"]
            }

            可用节点类型：
            - start: 工作流入口，只能有一个。
            - retriever: 知识库/文档/向量检索；config 可用 query、topK。
            - llm: 大模型回答、总结、分类、改写；config 可用 prompt、model、outputMode、outputSchema、writeState。
            - tool: 工具调用；config 可用 toolName、arguments、expression、idempotent。
            - http_request: 外部 HTTP API；必须使用真实 URL 和已有凭据 ID，不得用 tool 冒充。
            - report_export: 报告下载与打印；将可达上游文本输出生成 PDF、DOCX、HTML、Markdown 或 TXT。
            - custom: 安全的自由处理节点；AI 模式按 instruction 处理命名 inputs，template 模式只做确定性模板转换。
            - condition: 条件分支；config 可用 left、operator、right。
            - parallel: 并行开始；config 通常为空。
            - join: 并行汇合；config 通常为空。
            - variable_aggregator: 从互斥分支的上游输出中按顺序取第一个存在值，不是并行 join。
            - loop: 循环；config 可用 maxIterations、left、operator、right。
            - loop_back: 循环回边；config 通常为空。
            - subgraph: 子工作流；config 可用 definitionId、version。
            - dynamic: 动态工具/动作；config 必须包含 itemsFrom、allowedTools，可选 action。
            - end: 工作流结束，只能有一个。

            约束：
            - 节点 id 只能用英文、数字和下划线，例如 retriever_1、llm_summary。
            - 节点 label 必须是简洁中文业务名，例如“退货判断”“物流答复”；route 可填写同一业务流程名，例如“退货流程”。
            - 边 label 应描述业务走向，例如“是退货”“不是退货”“进入检索”；相关边也应填写相同 route，方便前端高亮整条路径。
            - label 和 route 是节点/边的顶层字段，不要放进 config。
            - 不要输出坐标、displayName、ui、position 等展示布局字段。
            - 必须输出 testInput，必须是一个对象，必须包含一条贴合用户需求的真实测试输入；默认字段用 message。
            - 只能使用动态上下文中列出的可执行工具；计算需求可使用 calculate，并设置 expression。
            - 调用任意外部 API 必须使用 http_request，不得发明 URL、credentialId 或响应字段。
            - http_request 的固定输出只有 statusCode、headers、body、json、durationMs、succeeded。
            - 未提供 URL 或凭据时，仍输出可编辑蓝图并在 notes 标记阻断项；禁止自行填写 example.com 或虚构 cred_xxx。
            - 用户要求“导出、下载、打印报告”时必须使用 report_export，不得用 tool、http_request 或虚构节点替代。
            - report_export.content 必须是精确的可达上游字符串变量，例如 {{nodes.llm_report.answer}}；不得直接使用 {{input.message}}。
            - 未指定格式时 report_export.formats 默认 ["pdf"]；明确要求多格式时一次写入 formats。
            - 禁止将报告文件或预览内容以 Base64 写入工作流 JSON、notes 或运行轨迹。
            - 用户需要现有专用节点未覆盖的分类、提取、归纳或文本组装时可使用 custom；必须声明 mode、命名 inputs 和明确 instruction 或 template。
            - custom 不能访问网络、执行代码、使用凭据或代替 http_request/tool；缺少数据时必须输出缺失原因，不得补造事实。
            - custom AI 模式需结构化输出时设置 outputMode:"json" 和 outputSchema；下游通过 {{nodes.<nodeId>.parsed.<field>}} 选择字段。
            - variable_aggregator 候选必须为当前节点可达上游的 {{nodes.<nodeId>.<field>}}，并声明匹配的 outputType。
            - 如果注册表中缺少所需能力，必须先澄清限制或诚实转人工，禁止用无关工具模拟执行或宣称操作成功。
            - 涉及知识库、文档、RAG、向量、检索时必须使用 retriever，再让 llm 使用 {{context}}。
            - llm prompt 应该明确引用 {{input}}，需要检索时引用 {{context}}，需要工具结果时引用 {{lastOutput}}。
            - 模板变量只能使用 {{input}}、{{input.xxx}}、{{context}}、{{lastOutput}}、{{lastOutput.xxx}}、{{toolResult}}、{{answer}}、{{state.xxx}}、{{nodes.<nodeId>}}、{{nodes.<nodeId>.<field>}}。
            - 工具或 Tavily 节点的完整输出可以写 {{nodes.tool_search}}，也可以按字段写 {{nodes.tool_search.results}}。
            - 引用某个 LLM 节点的回答必须写 {{nodes.llm_judge.answer}} 这种形式，不要写 {{llm_judge.output}}、{{llm_judge}} 或 {{nodes.llm_judge.output}}。
            - 如果下游条件要读取分类字段，分类 LLM 必须设置 outputMode:"json"，并提供 outputSchema.properties 中的稳定字段。
            - 引用结构化输出字段必须写 {{nodes.<nodeId>.parsed.<field>}}，并且上游 LLM 的 outputSchema 必须声明该 field。
            - 分类/路由节点必须同时设置 writeState，把关键字段写入流程状态，例如 {"sentiment":"{{lastOutput.parsed.sentiment}}"}。
            - 使用 {{state.xxx}} 前，必须有上游节点通过 writeState 写入同名 xxx；不确定时优先使用 {{nodes.<nodeId>.parsed.<field>}}。
            - 业务用户不需要手写 {{state.xxx}} 或 {{nodes.xxx}}；这些变量只在你生成的内部 JSON 中出现。
            - 必须保证从 start 到 end 有连通路径。
            - condition 节点必须刚好有两条出边，edge 必须分别设置 "condition":"true" 和 "condition":"false"。
            - 多于两个业务分支时，使用多个二选一 condition 串联，不要让一个 condition 直接分出三条边。
            - loop 节点必须刚好有 body/exit 两条出边，loop_back 必须回到对应 loop。
            - parallel 节点必须至少分出两条无条件分支；每个分支必须线性到同一个 join；join 至少两条入边且只能用于 parallel 汇合。
            - 如果用户只是要求“先判断再决定是否检索”，优先使用 condition true/false 分支；如果无法确信拓扑合法，用 llm 判断加 retriever/llm 的线性保守流程。

            Workflow Builder Spec：
            - 用户自然语言是产品需求，不是节点配置；你要补齐节点、连线、业务指令、结构化输出和分支条件。
            - 默认任务输入是用户消息，优先在 LLM prompt 中引用 {{input.message}}；不确定输入字段时可使用 {{input}}。
            - 分类字段名必须稳定英文，例如 sentiment、issueType、intent、priority。
            - 枚举值必须稳定英文，例如 positive、negative、after_sales、shipping、other。
            - outputSchema 字段要包含 title，方便前端变量图显示中文名。
            - 每个关键分类 LLM 的 prompt 必须要求“只输出合法 JSON，不要 Markdown、解释或额外文本”。

            客户评价系统标准编排：
            - 第一个 LLM 判断 sentiment，枚举 positive/negative，字段 sentiment、reason。
            - 正面分支生成品牌营销通知。
            - 负面分支再用 LLM 判断 issueType，枚举 after_sales/shipping/other，字段 issueType、reason。
            - issueType 分支必须用二选一 condition 串联：先判断 after_sales，再判断 shipping，剩余进入 other。
            - 售后分支生成售后部门工单；运输分支生成运输部门和产品体验部门通知；other 分支生成通用客服处理建议。
            """;

    private final AiModelService aiModelService;
    private final ObjectMapper objectMapper;
    private final WorkflowCompiler workflowCompiler;
    private final WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer;
    private final WorkflowRuntime workflowRuntime;
    private final TraceService traceService;
    private final WorkflowBuilderContextService workflowBuilderContextService;
    private final WorkflowGovernanceOrchestrator workflowGovernanceOrchestrator;
    private final WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry;
    private final HttpCredentialService httpCredentialService;

    public WorkflowGenerationService() {
        this(null, new ObjectMapper(), new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));
    }

    public WorkflowGenerationService(AiModelService aiModelService, ObjectMapper objectMapper,
            WorkflowCompiler workflowCompiler) {
        this(aiModelService, objectMapper, workflowCompiler, new WorkflowStructuredOutputAutoconfigurer(),
                null, null);
    }

    public WorkflowGenerationService(AiModelService aiModelService, ObjectMapper objectMapper,
            WorkflowCompiler workflowCompiler, WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer) {
        this(aiModelService, objectMapper, workflowCompiler, structuredOutputAutoconfigurer, null, null);
    }

    public WorkflowGenerationService(AiModelService aiModelService, ObjectMapper objectMapper,
            WorkflowCompiler workflowCompiler, WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            WorkflowRuntime workflowRuntime, TraceService traceService) {
        this(aiModelService, objectMapper, workflowCompiler, structuredOutputAutoconfigurer,
                workflowRuntime, traceService, null);
    }

    public WorkflowGenerationService(AiModelService aiModelService, ObjectMapper objectMapper,
            WorkflowCompiler workflowCompiler, WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            WorkflowRuntime workflowRuntime, TraceService traceService,
            WorkflowBuilderContextService workflowBuilderContextService) {
        this(aiModelService, objectMapper, workflowCompiler, structuredOutputAutoconfigurer,
                workflowRuntime, traceService, workflowBuilderContextService, null, null);
    }

    public WorkflowGenerationService(AiModelService aiModelService, ObjectMapper objectMapper,
            WorkflowCompiler workflowCompiler, WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            WorkflowRuntime workflowRuntime, TraceService traceService,
            WorkflowBuilderContextService workflowBuilderContextService,
            WorkflowGovernanceService workflowGovernanceService,
            WorkflowEvaluationService workflowEvaluationService) {
        this(aiModelService, objectMapper, workflowCompiler, structuredOutputAutoconfigurer,
                workflowRuntime, traceService, workflowBuilderContextService,
                workflowGovernanceService, workflowEvaluationService,
                workflowGovernanceService != null && workflowEvaluationService != null
                        ? new WorkflowRuleCatalog()
                        : null);
    }

    public WorkflowGenerationService(AiModelService aiModelService, ObjectMapper objectMapper,
            WorkflowCompiler workflowCompiler, WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            WorkflowRuntime workflowRuntime, TraceService traceService,
            WorkflowBuilderContextService workflowBuilderContextService,
            WorkflowGovernanceService workflowGovernanceService,
            WorkflowEvaluationService workflowEvaluationService,
            WorkflowRuleCatalog workflowRuleCatalog) {
        this(aiModelService, objectMapper, workflowCompiler, structuredOutputAutoconfigurer,
                workflowRuntime, traceService, workflowBuilderContextService,
                workflowGovernanceService, workflowEvaluationService, workflowRuleCatalog,
                workflowBuilderContextService != null
                                && workflowGovernanceService != null
                                && workflowEvaluationService != null
                                && workflowRuleCatalog != null
                        ? new WorkflowGovernanceOrchestrator(
                                structuredOutputAutoconfigurer,
                                workflowCompiler,
                                new WorkflowDefinitionContractValidator(),
                                workflowBuilderContextService,
                                workflowGovernanceService,
                                workflowEvaluationService,
                                workflowRuleCatalog,
                                objectMapper)
                        : null);
    }

    public WorkflowGenerationService(AiModelService aiModelService, ObjectMapper objectMapper,
            WorkflowCompiler workflowCompiler, WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            WorkflowRuntime workflowRuntime, TraceService traceService,
            WorkflowBuilderContextService workflowBuilderContextService,
            WorkflowGovernanceService workflowGovernanceService,
            WorkflowEvaluationService workflowEvaluationService,
            WorkflowRuleCatalog workflowRuleCatalog,
            WorkflowGovernanceOrchestrator workflowGovernanceOrchestrator) {
        this(aiModelService, objectMapper, workflowCompiler, structuredOutputAutoconfigurer, workflowRuntime,
                traceService, workflowBuilderContextService, workflowGovernanceService, workflowEvaluationService,
                workflowRuleCatalog, workflowGovernanceOrchestrator, new WorkflowNodeSchemaRegistry(), null);
    }

    @Autowired
    public WorkflowGenerationService(AiModelService aiModelService, ObjectMapper objectMapper,
            WorkflowCompiler workflowCompiler, WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            WorkflowRuntime workflowRuntime, TraceService traceService,
            WorkflowBuilderContextService workflowBuilderContextService,
            WorkflowGovernanceService workflowGovernanceService,
            WorkflowEvaluationService workflowEvaluationService,
            WorkflowRuleCatalog workflowRuleCatalog,
            WorkflowGovernanceOrchestrator workflowGovernanceOrchestrator,
            WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry,
            HttpCredentialService httpCredentialService) {
        this.aiModelService = aiModelService;
        this.objectMapper = objectMapper;
        this.workflowCompiler = workflowCompiler;
        this.structuredOutputAutoconfigurer = structuredOutputAutoconfigurer;
        this.workflowRuntime = workflowRuntime;
        this.traceService = traceService;
        this.workflowBuilderContextService = workflowBuilderContextService;
        this.workflowGovernanceOrchestrator = workflowGovernanceOrchestrator;
        this.workflowNodeSchemaRegistry = workflowNodeSchemaRegistry;
        this.httpCredentialService = httpCredentialService;
    }

    public WorkflowGenerationResponse generate(WorkflowGenerationRequest request) {
        String prompt = request.prompt().trim();
        if (aiModelService == null) {
            throw new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                    "Alibaba LLM is required for workflow generation");
        }
        return generateWithAi(prompt, normalizeLockedSpec(request.lockedSpec()), null);
    }

    public WorkflowGenerationResponse edit(WorkflowEditRequest request) {
        if (aiModelService == null) {
            throw new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                    "Alibaba LLM is required for workflow editing");
        }
        return generateWithAi(editPrompt(request), normalizeLockedSpec(request.lockedSpec()), null);
    }

    public WorkflowGenerationResponse repair(WorkflowRepairRequest request) {
        if (aiModelService == null) {
            throw new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                    "Alibaba LLM is required for workflow repair");
        }
        return generateWithAi(repairCurrentWorkflowPrompt(request), normalizeLockedSpec(request.lockedSpec()),
                request.error());
    }

    public WorkflowGovernanceEvaluationResponse evaluateGovernance(WorkflowGovernanceEvaluationRequest request) {
        if (workflowGovernanceOrchestrator == null) {
            throw new BusinessException("WORKFLOW_GOVERNANCE_NOT_CONFIGURED",
                    "Workflow governance evaluation is not configured");
        }
        return workflowGovernanceOrchestrator.evaluate(
                request.workflowDefinition(), request.lockedSpec(), request.supplementalInput());
    }

    public WorkflowGenerationResponse generateStreaming(WorkflowGenerationRequest request,
            BiConsumer<String, Map<String, Object>> stream) {
        String prompt = request.prompt().trim();
        if (aiModelService == null) {
            throw new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                    "Alibaba LLM is required for workflow generation");
        }
        return generateWithAiStreaming(prompt, normalizeLockedSpec(request.lockedSpec()), null, "编排", stream);
    }

    public WorkflowGenerationResponse editStreaming(WorkflowEditRequest request,
            BiConsumer<String, Map<String, Object>> stream) {
        if (aiModelService == null) {
            throw new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                    "Alibaba LLM is required for workflow editing");
        }
        return generateWithAiStreaming(editPrompt(request), normalizeLockedSpec(request.lockedSpec()), null,
                "修改", stream);
    }

    public WorkflowGenerationResponse repairStreaming(WorkflowRepairRequest request,
            BiConsumer<String, Map<String, Object>> stream) {
        if (aiModelService == null) {
            throw new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                    "Alibaba LLM is required for workflow repair");
        }
        return generateWithAiStreaming(repairCurrentWorkflowPrompt(request), normalizeLockedSpec(request.lockedSpec()),
                request.error(), "修复", stream);
    }

    private WorkflowGenerationResponse generateWithAi(String prompt, JsonNode lockedSpec, String initialFailure) {
        requireGovernance();
        String previousAnswer = "";
        Exception lastError = null;
        WorkflowGenerationResponse lastBlockedResponse = null;
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                WorkflowBuilderContext builderContext = buildBuilderContext(lockedSpec, prompt,
                        attempt == 1 ? initialFailure : errorMessage(lastError));
                AiModelResult result = aiModelService.generate(systemPrompt(), attempt == 1
                        ? userPrompt(prompt, builderContext)
                        : repairPrompt(prompt, previousAnswer, lastError, attempt - 1, builderContext));
                previousAnswer = result.answer();
                return validatedAiResponse(result, builderContext, attempt - 1, lockedSpec);
            }
            catch (WorkflowCandidateRejectedException error) {
                lastError = error;
                lastBlockedResponse = error.response();
            }
            catch (Exception error) {
                lastError = error;
                if (isProviderFailure(error)) {
                    return preserveCandidateBlueprint(lastBlockedResponse,
                            infrastructureFailureResponse(prompt, error, attempt - 1, lockedSpec));
                }
                lastBlockedResponse = preserveCandidateBlueprint(lastBlockedResponse,
                        validationFailureResponse(prompt, error, attempt - 1, lockedSpec));
            }
        }
        if (lastBlockedResponse != null) {
            return lastBlockedResponse;
        }
        throw new BusinessException("WORKFLOW_GENERATION_FAILED",
                "AI workflow generation failed after " + (MAX_GENERATION_ATTEMPTS - 1)
                        + " repair attempts: " + (lastError == null ? "unknown error" : lastError.getMessage()),
                lastError);
    }

    private WorkflowGenerationResponse generateWithAiStreaming(String prompt, JsonNode lockedSpec,
            String initialFailure, String action, BiConsumer<String, Map<String, Object>> stream) {
        requireGovernance();
        String previousAnswer = "";
        Exception lastError = null;
        WorkflowGenerationResponse lastBlockedResponse = null;
        String modelName = aiModelService.modelName();
        for (int attempt = 1; attempt <= MAX_GENERATION_ATTEMPTS; attempt++) {
            try {
                WorkflowBuilderContext builderContext = buildBuilderContext(lockedSpec, prompt,
                        attempt == 1 ? initialFailure : errorMessage(lastError));
                if (attempt == 1) {
                    sendStatus(stream, "MODEL_GENERATION", "IN_PROGRESS",
                            "正在请求 " + (modelName == null || modelName.isBlank() ? "AI" : modelName)
                                    + " " + action + "工作流");
                    previousAnswer = streamModel(userPrompt(prompt, builderContext), "draft", stream);
                    sendStatus(stream, "GOVERNANCE_EVALUATION", "IN_PROGRESS",
                            "AI 输出完成，正在按保存规则归一化、校验并自动试运行");
                }
                else {
                    sendStatus(stream, "REPAIR", "IN_PROGRESS",
                            "AI 第 " + (attempt - 1) + " 次输出未通过校验，正在自动修复");
                    previousAnswer = streamModel(
                            repairPrompt(prompt, previousAnswer, lastError, attempt - 1, builderContext),
                            "repair_" + (attempt - 1), stream);
                    sendStatus(stream, "GOVERNANCE_EVALUATION", "IN_PROGRESS",
                            "第 " + (attempt - 1) + " 次修复稿输出完成，正在再次归一化、校验并自动试运行");
                }
                WorkflowGenerationResponse response = validatedAiResponse(
                        AiModelResult.ok(previousAnswer), builderContext, attempt - 1, lockedSpec);
                sendStatus(stream, "COMPLETE", response.status().name(), completionMessage(response.status()));
                return response;
            }
            catch (WorkflowCandidateRejectedException error) {
                lastError = error;
                lastBlockedResponse = error.response();
            }
            catch (Exception error) {
                lastError = error;
                if (isProviderFailure(error)) {
                    WorkflowGenerationResponse response = preserveCandidateBlueprint(lastBlockedResponse,
                            infrastructureFailureResponse(prompt, error, attempt - 1, lockedSpec));
                    sendStatus(stream, "COMPLETE", response.status().name(), completionMessage(response.status()));
                    return response;
                }
                lastBlockedResponse = preserveCandidateBlueprint(lastBlockedResponse,
                        validationFailureResponse(prompt, error, attempt - 1, lockedSpec));
            }
        }
        if (lastBlockedResponse != null) {
            sendStatus(stream, "COMPLETE", WorkflowGenerationStatus.BLOCKED.name(),
                    "AI 多轮修复后仍未通过治理检查，已保留检查报告");
            return lastBlockedResponse;
        }
        sendStatus(stream, "COMPLETE", WorkflowGenerationStatus.BLOCKED.name(),
                "AI 多轮修复后仍未通过校验，已停止生成");
        throw new BusinessException("WORKFLOW_GENERATION_FAILED",
                "AI workflow generation failed after " + (MAX_GENERATION_ATTEMPTS - 1)
                        + " repair attempts: " + (lastError == null ? "unknown error" : lastError.getMessage()),
                lastError);
    }

    private String streamModel(String userMessage, String attempt, BiConsumer<String, Map<String, Object>> stream) {
        StringBuilder answer = new StringBuilder();
        try {
            aiModelService.streamUntilComplete(systemPrompt(), userMessage, chunk -> {
                answer.append(chunk);
                String phase = attempt.startsWith("repair_") ? "REPAIR" : "MODEL_GENERATION";
                stream.accept("message", Map.of(
                        "attempt", attempt,
                        "delta", chunk,
                        "phase", phase,
                        "status", "IN_PROGRESS"));
            }, () -> containsCompleteJson(answer));
        }
        catch (BusinessException exception) {
            if (!"ALIBABA_LLM_UNAVAILABLE".equals(exception.getCode()) || !containsCompleteJson(answer)) {
                throw exception;
            }
            sendStatus(stream,
                    attempt.startsWith("repair_") ? "REPAIR" : "MODEL_GENERATION",
                    "IN_PROGRESS",
                    "模型已返回完整 JSON；流结束信号超时，继续执行治理检查");
        }
        if (answer.isEmpty()) {
            throw new IllegalArgumentException("模型没有返回内容");
        }
        return answer.toString();
    }

    private boolean containsCompleteJson(CharSequence answer) {
        if (answer == null || answer.isEmpty()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(extractJson(answer.toString()));
            return root != null && root.isObject();
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private void sendStatus(BiConsumer<String, Map<String, Object>> stream, String phase, String status,
            String message) {
        stream.accept("status", Map.of("phase", phase, "status", status, "message", message));
    }

    private String completionMessage(WorkflowGenerationStatus status) {
        return switch (status) {
            case READY -> "工作流已通过治理检查和自动测试";
            case BLOCKED -> "工作流未通过治理检查，已保留结构化检查报告";
            case INFRA_ERROR -> "治理基础设施暂时不可用，当前画布不会被替换";
        };
    }

    private String userPrompt(String prompt, WorkflowBuilderContext builderContext) {
        return """
                用户需求：
                %s

                %s

                请直接返回符合约束的 JSON。
                """.formatted(prompt, contextSection(builderContext) + credentialContextSection());
    }

    private String systemPrompt() {
        return SYSTEM_PROMPT + "\n\n当前注册表（唯一权威节点目录）：\n"
                + workflowNodeSchemaRegistry.generationCatalog();
    }

    private String credentialContextSection() {
        if (httpCredentialService == null) {
            return "";
        }
        try {
            List<HttpCredentialResponse> credentials = httpCredentialService.list();
            if (credentials.isEmpty()) {
                return "\n已配置 HTTP 凭据：无。不得发明 credentialId。\n";
            }
            StringBuilder section = new StringBuilder("\n已配置 HTTP 凭据元数据（可按名称匹配，不含密钥）：\n");
            credentials.forEach(credential -> section.append("- ")
                    .append(credential.credentialId()).append(" | ")
                    .append(credential.name()).append(" | ")
                    .append(credential.type()).append('\n'));
            return section.toString();
        }
        catch (RuntimeException ignored) {
            return "\nHTTP 凭据元数据暂时不可用；不得发明 credentialId。\n";
        }
    }

    private String editPrompt(WorkflowEditRequest request) {
        String currentWorkflowJson;
        try {
            currentWorkflowJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(request.workflowDefinition());
        }
        catch (Exception error) {
            throw new IllegalArgumentException("当前工作流无法序列化：" + error.getMessage(), error);
        }
        return """
                工作流编辑任务：
                你要基于“当前工作流 JSON”进行修改，而不是从零重建。

                当前工作流名称：
                %s

                当前工作流描述：
                %s

                当前工作流 JSON：
                %s

                用户编辑指令：
                %s

                编辑要求：
                - 返回完整的 JSON 响应，包含 name、description、testInput、workflowDefinition、notes。
                - workflowDefinition 必须是修改后的完整工作流，不要只返回 diff、patch、片段或说明。
                - 除非用户明确要求删除或重构，否则保留当前节点、连线、label、route、config、结构化输出和状态写入。
                - 如果新增分支或节点，必须补齐合法连线，并保证从 start 到 end 有连通路径。
                - 如果修改条件或结构化字段，必须同时更新 outputSchema、writeState 和所有相关模板变量。
                - testInput 必须贴合编辑后的工作流，用来自动试运行修改结果。
                """.formatted(
                cleanEditText(request.name(), "当前画布工作流"),
                cleanEditText(request.description(), "来自当前画布"),
                currentWorkflowJson,
                request.prompt().trim());
    }

    private String cleanEditText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private String repairCurrentWorkflowPrompt(WorkflowRepairRequest request) {
        String currentWorkflowJson;
        try {
            currentWorkflowJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(request.workflowDefinition());
        }
        catch (Exception error) {
            throw new IllegalArgumentException("当前工作流无法序列化：" + error.getMessage(), error);
        }
        return """
                工作流修复任务：
                你要基于“当前工作流 JSON”和“最新错误”修复现有画布，而不是从零随意重建。

                当前工作流名称：
                %s

                当前工作流描述：
                %s

                用户原始需求或补充说明：
                %s

                最新错误 / 异常现象：
                %s

                当前工作流 JSON：
                %s

                修复要求：
                - 返回完整的 JSON 响应，包含 name、description、testInput、workflowDefinition、notes。
                - workflowDefinition 必须是修复后的完整工作流，不要只返回 diff、patch、片段或说明。
                - 只能使用系统提示里列出的现有节点类型，不要发明新节点类型。
                - 优先保留现有节点、业务分支、label、route 和用户已经填写的业务意图。
                - 如果错误是模板变量或状态变量错误，必须同时修复 outputSchema、writeState、condition left/right 和相关 prompt。
                - 如果错误是客户对话里看到了裸 JSON，中间分类节点可以继续输出 JSON，但最终处理节点必须输出客户可读的自然语言结果。
                - 如果不确定复杂拓扑是否合法，使用更保守的线性或二选一 condition 串联流程。
                - testInput 必须是一条真实用户输入，用来自动试运行修复后的工作流。

                %s
                """.formatted(
                cleanEditText(request.name(), "当前画布工作流"),
                cleanEditText(request.description(), "来自当前画布"),
                cleanEditText(request.prompt(), "用户要求修复当前工作流"),
                request.error().trim(),
                currentWorkflowJson,
                SUPERPOWERS_REPAIR_INSTRUCTIONS);
    }

    private String repairPrompt(String prompt, String previousAnswer, Exception error, int repairAttempt,
            WorkflowBuilderContext builderContext) {
        return """
                用户需求：
                %s

                你上一次输出的工作流没有通过系统校验，这是第 %d 次自动修复。
                校验错误：
                %s

                上一次输出：
                %s

                请重新编排一个更保守、可通过校验的工作流 JSON。
                重要：如果不确定条件/并行/汇合的合法拓扑，优先使用线性流程，不要使用 join。
                重要：如果使用 {{state.xxx}}，必须保证上游节点 config.writeState 写入了同名 xxx；否则改用 {{nodes.<nodeId>.parsed.<field>}} 并补齐 outputMode/outputSchema/writeState。
                重要：如果错误来自自动测试运行，请根据真实 testInput 和运行错误重新调整节点、提示词、结构化输出、条件或连线。

                %s
                %s
                只输出 JSON。
                """.formatted(prompt, repairAttempt, error == null ? "unknown error" : error.getMessage(),
                previousAnswer,
                SUPERPOWERS_REPAIR_INSTRUCTIONS,
                contextSection(builderContext));
    }

    private JsonNode normalizeLockedSpec(JsonNode lockedSpec) {
        return WorkflowLockedSpecCodec.normalize(objectMapper, lockedSpec);
    }

    private WorkflowBuilderContext buildBuilderContext(JsonNode lockedSpec, String fallbackPrompt,
            String previousFailure) {
        if (workflowBuilderContextService == null) {
            return null;
        }
        String contextSpec = lockedSpec == null
                ? fallbackPrompt
                : WorkflowLockedSpecCodec.contextText(objectMapper, lockedSpec);
        return workflowBuilderContextService.build(null, contextSpec, previousFailure);
    }

    private String contextSection(WorkflowBuilderContext builderContext) {
        return builderContext == null ? "" : builderContext.promptSection();
    }

    private String errorMessage(Exception error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private WorkflowGenerationResponse validatedAiResponse(AiModelResult modelResult,
            WorkflowBuilderContext builderContext, int repairAttempts, JsonNode lockedSpec)
            throws Exception {
        ParsedGeneratedWorkflow response = parseModelResponse(modelResult.answer());
        List<String> notes = response.notes() == null ? List.of() : response.notes();
        List<WorkflowGovernanceFinding> additionalFindings = repairAttempts > 0 && !hasSuperpowersEvidence(notes)
                ? List.of(superpowersEvidenceFinding())
                : List.of();
        WorkflowGovernanceEvaluationResponse evaluation = workflowGovernanceOrchestrator.evaluate(
                response.workflowDefinition(), builderContext, response.testInput(), additionalFindings);
        WorkflowGenerationResponse governed = governedResponse(
                response, notes, evaluation, repairAttempts, lockedSpec);
        if (governed.status() == WorkflowGenerationStatus.BLOCKED
                || hasRepairableDesignFailure(governed.testResults())
                || hasRepairableCaseDeadline(governed.testResults())) {
            throw new WorkflowCandidateRejectedException(governed,
                    "工作流治理未通过：" + compactJson(evaluation));
        }
        return governed;
    }

    private boolean hasRepairableDesignFailure(List<WorkflowEvaluationCaseResult> testResults) {
        return testResults != null && testResults.stream()
                .map(WorkflowEvaluationCaseResult::status)
                .anyMatch(WorkflowEvaluationCaseStatus.DESIGN_FAILED::equals);
    }

    private boolean hasRepairableCaseDeadline(List<WorkflowEvaluationCaseResult> testResults) {
        return testResults != null && testResults.stream()
                .map(WorkflowEvaluationCaseResult::errorCode)
                .anyMatch("EVALUATION_CASE_DEADLINE_EXCEEDED"::equals);
    }

    private void requireGovernance() {
        if (workflowGovernanceOrchestrator == null) {
            throw new BusinessException("WORKFLOW_GOVERNANCE_NOT_CONFIGURED",
                    "Workflow governance is required for generation, editing, and repair");
        }
    }

    private WorkflowGovernanceFinding superpowersEvidenceFinding() {
        return new WorkflowGovernanceFinding(
                "core-superpowers-repair-evidence",
                WorkflowGovernanceFinding.Severity.BLOCK,
                WorkflowGovernanceFinding.Phase.STATIC,
                "修复稿缺少 Diagnose -> Plan -> Implement -> Verify 的完整证据：诊断、计划、实际改动、验证。",
                List.of(),
                "在 notes 中分别提供诊断结论、修复计划、实际改动和验证样例。",
                Map.of("requiredEvidence", List.of("diagnose", "plan", "implement", "verify")));
    }

    private boolean hasSuperpowersEvidence(List<String> notes) {
        String evidence = String.join("\n", notes).toLowerCase(java.util.Locale.ROOT);
        boolean diagnose = containsAny(evidence, "诊断", "根因", "diagnose");
        boolean plan = containsAny(evidence, "计划", "plan");
        boolean implement = containsAny(evidence, "实际改动", "改动", "修改", "实现", "implement");
        boolean verify = containsAny(evidence, "验证", "测试", "verify");
        return diagnose && plan && implement && verify;
    }

    private boolean containsAny(String text, String... values) {
        return Arrays.stream(values).anyMatch(text::contains);
    }

    private WorkflowGenerationResponse governedResponse(ParsedGeneratedWorkflow response,
            List<String> notes,
            WorkflowGovernanceEvaluationResponse evaluation,
            int repairAttempts,
            JsonNode lockedSpec) {
        return new WorkflowGenerationResponse(
                response.name(),
                response.description(),
                evaluation.workflowDefinition(),
                notes,
                legacyTestResult(evaluation.testResults()),
                evaluation.status(),
                evaluation.governanceReport(),
                evaluation.testResults(),
                repairAttempts,
                evaluation.activeRulePacks(),
                lockedSpec);
    }

    private WorkflowGenerationTestResult legacyTestResult(List<WorkflowEvaluationCaseResult> testResults) {
        return testResults.stream()
                .filter(result -> result.status() == WorkflowEvaluationCaseStatus.PASSED)
                .findFirst()
                .map(result -> new WorkflowGenerationTestResult(
                        result.input(),
                        result.output(),
                        result.attemptRunIds().isEmpty() ? null : result.attemptRunIds().getLast(),
                        result.executedPath().size()))
                .orElse(null);
    }

    private String compactJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (Exception error) {
            return String.valueOf(value);
        }
    }

    private WorkflowGenerationResponse validationFailureResponse(String prompt, Exception error,
            int repairAttempts, JsonNode lockedSpec) {
        String message = errorMessage(error);
        WorkflowGovernanceFinding finding = new WorkflowGovernanceFinding(
                "core-workflow-validity",
                WorkflowGovernanceFinding.Severity.BLOCK,
                WorkflowGovernanceFinding.Phase.STATIC,
                message,
                List.of(),
                "Return one complete workflow JSON object that compiles against the registered node catalog.",
                Map.of("errorType", error.getClass().getSimpleName()));
        return new WorkflowGenerationResponse(
                "工作流生成未完成",
                prompt,
                null,
                List.of(message),
                null,
                WorkflowGenerationStatus.BLOCKED,
                new WorkflowGovernanceReport(List.of(finding)),
                List.of(),
                repairAttempts,
                List.of(),
                lockedSpec);
    }

    private WorkflowGenerationResponse preserveCandidateBlueprint(WorkflowGenerationResponse candidate,
            WorkflowGenerationResponse failure) {
        if (failure.workflowDefinition() != null
                || candidate == null
                || candidate.workflowDefinition() == null) {
            return failure;
        }
        List<String> notes = new ArrayList<>(candidate.notes());
        failure.notes().stream()
                .filter(note -> !notes.contains(note))
                .forEach(notes::add);
        return new WorkflowGenerationResponse(
                candidate.name(),
                candidate.description(),
                candidate.workflowDefinition(),
                notes,
                candidate.testResult(),
                failure.status(),
                candidate.governanceReport(),
                candidate.testResults(),
                failure.repairAttempts(),
                candidate.activeRulePacks(),
                failure.lockedSpec() == null ? candidate.lockedSpec() : failure.lockedSpec());
    }

    private WorkflowGenerationResponse infrastructureFailureResponse(String prompt, Exception error,
            int repairAttempts, JsonNode lockedSpec) {
        return new WorkflowGenerationResponse(
                "工作流生成暂时不可用",
                prompt,
                null,
                List.of(errorMessage(error)),
                null,
                WorkflowGenerationStatus.INFRA_ERROR,
                new WorkflowGovernanceReport(List.of()),
                List.of(),
                repairAttempts,
                List.of(),
                lockedSpec);
    }

    private boolean isProviderFailure(Exception error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof BusinessException businessException
                    && businessException.getCode() != null
                    && businessException.getCode().startsWith("ALIBABA_")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ParsedGeneratedWorkflow parseModelResponse(String answer) throws Exception {
        String json = extractJson(answer);
        JsonNode root = objectMapper.readTree(json);
        WorkflowDefinition definition = objectMapper.convertValue(cleanDefinitionNode(root.path("workflowDefinition")),
                WorkflowDefinition.class);
        String name = requiredText(root, "name");
        String description = requiredText(root, "description");
        Map<String, Object> testInput = parseTestInput(root);
        List<String> notes = new ArrayList<>();
        JsonNode notesNode = root.path("notes");
        if (notesNode.isArray()) {
            notesNode.forEach(note -> notes.add(note.asText()));
        }
        return new ParsedGeneratedWorkflow(name, description, definition, notes, testInput);
    }

    private Map<String, Object> parseTestInput(JsonNode root) {
        JsonNode testInputNode = root.path("testInput");
        if (!testInputNode.isObject() || testInputNode.isEmpty()) {
            return Map.of();
        }
        return objectMapper.convertValue(testInputNode, new TypeReference<>() {
        });
    }

    private WorkflowGenerationTestResult runAutomaticTest(String workflowName, WorkflowDefinition definition,
            WorkflowExecutionPlan executionPlan, Map<String, Object> testInput) {
        if (workflowRuntime == null || traceService == null) {
            return null;
        }
        if (testInput == null || testInput.isEmpty()) {
            throw new IllegalArgumentException("模型返回缺少自动测试输入 testInput，请生成一条真实用户输入并重新输出 JSON");
        }
        TraceRun run = traceService.startRun(RunType.WORKFLOW, Map.of(
                "kind", "workflow_generation_smoke_test",
                "workflowName", workflowName,
                "testInput", testInput));
        try {
            WorkflowRuntime.WorkflowExecutionResult result =
                    workflowRuntime.run(run.runId(), executionPlan, testInput);
            int stepCount = result.steps() == null ? 0 : result.steps().size();
            if (stepCount == 0) {
                throw new IllegalArgumentException("自动测试运行没有产生任何节点执行记录");
            }
            traceService.markRunSucceeded(run.runId(), result.output());
            return new WorkflowGenerationTestResult(testInput, result.output(), run.runId(), stepCount);
        }
        catch (RuntimeException error) {
            traceService.markRunFailed(run.runId(), error);
            throw new IllegalArgumentException("自动测试运行失败：" + error.getMessage(), error);
        }
    }

    private record ParsedGeneratedWorkflow(
            String name,
            String description,
            WorkflowDefinition workflowDefinition,
            List<String> notes,
            Map<String, Object> testInput) {
    }

    private static final class WorkflowCandidateRejectedException extends Exception {

        private final WorkflowGenerationResponse response;

        private WorkflowCandidateRejectedException(WorkflowGenerationResponse response, String message) {
            super(message);
            this.response = response;
        }

        private WorkflowGenerationResponse response() {
            return response;
        }
    }

    private String requiredText(JsonNode root, String fieldName) {
        String value = root.path(fieldName).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("模型返回缺少必填字段：" + fieldName);
        }
        return value.trim();
    }

    private JsonNode cleanDefinitionNode(JsonNode definitionNode) {
        JsonNode copy = definitionNode.deepCopy();
        JsonNode nodes = copy.path("nodes");
        if (!nodes.isArray()) {
            return copy;
        }
        nodes.forEach(node -> {
            JsonNode config = node.path("config");
            if (config instanceof ObjectNode configObject) {
                List<String> nullKeys = new ArrayList<>();
                Iterator<Map.Entry<String, JsonNode>> fields = configObject.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (field.getValue() == null || field.getValue().isNull()) {
                        nullKeys.add(field.getKey());
                    }
                }
                nullKeys.forEach(configObject::remove);
            }
        });
        return copy;
    }

    private String extractJson(String answer) {
        String text = answer == null ? "" : answer.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型没有返回 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

}
