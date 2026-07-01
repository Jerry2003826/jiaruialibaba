package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class WorkflowGenerationService {

    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("\\{\\{\\s*([a-zA-Z][a-zA-Z0-9_.-]*)\\s*}}");

    private static final String SYSTEM_PROMPT = """
            你是工作流编排器。你必须根据用户的自然语言需求，自主设计一个可运行的工作流。
            只输出 JSON，不要输出 Markdown、解释、代码块或额外文本。

            JSON 结构必须是：
            {
              "name": "简短中文名称",
              "description": "一句话描述",
              "workflowDefinition": {
                "nodes": [
                  {"id":"start","type":"start","config":{}},
                  {"id":"llm_1","type":"llm","config":{"prompt":"..."}},
                  {"id":"end","type":"end","config":{}}
                ],
                "edges": [{"from":"start","to":"llm_1"},{"from":"llm_1","to":"end"}]
              },
              "notes": ["一句中文说明"]
            }

            可用节点类型：
            - start: 工作流入口，只能有一个。
            - retriever: 知识库/文档/向量检索；config 可用 query、topK。
            - llm: 大模型回答、总结、分类、改写；config 可用 prompt、model。
            - tool: 工具调用；config 可用 toolName、arguments、expression、idempotent。
            - condition: 条件分支；config 可用 left、operator、right。
            - parallel: 并行开始；config 通常为空。
            - join: 并行汇合；config 通常为空。
            - loop: 循环；config 可用 maxIterations、left、operator、right。
            - loop_back: 循环回边；config 通常为空。
            - subgraph: 子工作流；config 可用 definitionId、version。
            - dynamic: 动态工具/动作；config 可用 itemsFrom、action。
            - end: 工作流结束，只能有一个。

            约束：
            - 节点 id 只能用英文、数字和下划线，例如 retriever_1、llm_summary。
            - 不要输出坐标、label、displayName、ui、position 等非 config 字段。
            - 不确定工具名时，优先使用 getCurrentTime；计算需求使用 calculate，并设置 expression。
            - 涉及知识库、文档、RAG、向量、检索时必须使用 retriever，再让 llm 使用 {{context}}。
            - llm prompt 应该明确引用 {{input}}，需要检索时引用 {{context}}，需要工具结果时引用 {{lastOutput}}。
            - 模板变量只能使用 {{input}}、{{input.xxx}}、{{context}}、{{lastOutput}}、{{lastOutput.xxx}}、{{toolResult}}、{{answer}}、{{nodes.<nodeId>.<field>}}。
            - 引用某个 LLM 节点的回答必须写 {{nodes.llm_judge.answer}} 这种形式，不要写 {{llm_judge.output}}、{{llm_judge}} 或 {{nodes.llm_judge.output}}。
            - 必须保证从 start 到 end 有连通路径。
            - condition 节点必须刚好有两条出边，edge 必须分别设置 "condition":"true" 和 "condition":"false"。
            - loop 节点必须刚好有 body/exit 两条出边，loop_back 必须回到对应 loop。
            - parallel 节点必须至少分出两条无条件分支；每个分支必须线性到同一个 join；join 至少两条入边且只能用于 parallel 汇合。
            - 如果用户只是要求“先判断再决定是否检索”，优先使用 condition true/false 分支；如果无法确信拓扑合法，用 llm 判断加 retriever/llm 的线性保守流程。
            """;

    private final AiModelService aiModelService;
    private final ObjectMapper objectMapper;
    private final WorkflowCompiler workflowCompiler;

    public WorkflowGenerationService() {
        this(null, new ObjectMapper(), new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));
    }

    @Autowired
    public WorkflowGenerationService(AiModelService aiModelService, ObjectMapper objectMapper,
            WorkflowCompiler workflowCompiler) {
        this.aiModelService = aiModelService;
        this.objectMapper = objectMapper;
        this.workflowCompiler = workflowCompiler;
    }

    public WorkflowGenerationResponse generate(WorkflowGenerationRequest request) {
        String prompt = request.prompt().trim();
        if (aiModelService == null) {
            throw new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                    "Alibaba LLM is required for workflow generation");
        }
        return generateWithAi(prompt);
    }

    public WorkflowGenerationResponse generateStreaming(WorkflowGenerationRequest request,
            BiConsumer<String, Map<String, Object>> stream) {
        String prompt = request.prompt().trim();
        if (aiModelService == null) {
            throw new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                    "Alibaba LLM is required for workflow generation");
        }
        return generateWithAiStreaming(prompt, stream);
    }

    private WorkflowGenerationResponse generateWithAi(String prompt) {
        AiModelResult firstResult = null;
        try {
            firstResult = aiModelService.generate(SYSTEM_PROMPT, userPrompt(prompt));
            return validatedAiResponse(prompt, firstResult, false);
        }
        catch (Exception firstError) {
            try {
                AiModelResult repairedResult = aiModelService.generate(SYSTEM_PROMPT,
                        repairPrompt(prompt, firstResult == null ? "" : firstResult.answer(), firstError));
                return validatedAiResponse(prompt, repairedResult, true);
            }
            catch (Exception secondError) {
                throw new BusinessException("WORKFLOW_GENERATION_FAILED",
                        "AI workflow generation failed after repair: " + secondError.getMessage(), secondError);
            }
        }
    }

    private WorkflowGenerationResponse generateWithAiStreaming(String prompt,
            BiConsumer<String, Map<String, Object>> stream) {
        String firstAnswer = "";
        try {
            String modelName = aiModelService.modelName();
            sendStatus(stream, "正在请求 " + (modelName == null || modelName.isBlank() ? "AI" : modelName)
                    + " 编排工作流");
            firstAnswer = streamModel(userPrompt(prompt), "draft", stream);
            sendStatus(stream, "AI 输出完成，正在校验拓扑和模板变量");
            return validatedAiResponse(prompt, AiModelResult.ok(firstAnswer), false);
        }
        catch (Exception firstError) {
            try {
                sendStatus(stream, "AI 初稿未通过校验，正在带错误信息自动修复");
                String repairedAnswer = streamModel(repairPrompt(prompt, firstAnswer, firstError), "repair", stream);
                sendStatus(stream, "修复稿输出完成，正在再次校验");
                return validatedAiResponse(prompt, AiModelResult.ok(repairedAnswer), true);
            }
            catch (Exception secondError) {
                sendStatus(stream, "AI 修复失败，已停止生成");
                throw new BusinessException("WORKFLOW_GENERATION_FAILED",
                        "AI workflow generation failed after repair: " + secondError.getMessage(), secondError);
            }
        }
    }

    private String streamModel(String userMessage, String attempt, BiConsumer<String, Map<String, Object>> stream) {
        StringBuilder answer = new StringBuilder();
        aiModelService.stream(SYSTEM_PROMPT, userMessage, chunk -> {
            answer.append(chunk);
            stream.accept("message", Map.of("attempt", attempt, "delta", chunk));
        });
        if (answer.isEmpty()) {
            throw new IllegalArgumentException("模型没有返回内容");
        }
        return answer.toString();
    }

    private void sendStatus(BiConsumer<String, Map<String, Object>> stream, String message) {
        stream.accept("status", Map.of("message", message));
    }

    private String userPrompt(String prompt) {
        return """
                用户需求：
                %s

                请直接返回符合约束的 JSON。
                """.formatted(prompt);
    }

    private String repairPrompt(String prompt, String previousAnswer, Exception error) {
        return """
                用户需求：
                %s

                你上一次输出的工作流没有通过系统校验。
                校验错误：
                %s

                上一次输出：
                %s

                请重新编排一个更保守、可通过校验的工作流 JSON。
                重要：如果不确定条件/并行/汇合的合法拓扑，优先使用线性流程，不要使用 join。
                只输出 JSON。
                """.formatted(prompt, error.getMessage(), previousAnswer);
    }

    private WorkflowGenerationResponse validatedAiResponse(String prompt, AiModelResult modelResult, boolean repaired)
            throws Exception {
        WorkflowGenerationResponse response = parseModelResponse(modelResult.answer());
        workflowCompiler.compile(response.workflowDefinition());
        validateTemplateVariables(response.workflowDefinition());
        List<String> notes = response.notes() == null ? List.of() : response.notes();
        return new WorkflowGenerationResponse(
                response.name(),
                response.description(),
                response.workflowDefinition(),
                notes);
    }

    private WorkflowGenerationResponse parseModelResponse(String answer) throws Exception {
        String json = extractJson(answer);
        JsonNode root = objectMapper.readTree(json);
        WorkflowDefinition definition = objectMapper.convertValue(cleanDefinitionNode(root.path("workflowDefinition")),
                WorkflowDefinition.class);
        String name = requiredText(root, "name");
        String description = requiredText(root, "description");
        List<String> notes = new ArrayList<>();
        JsonNode notesNode = root.path("notes");
        if (notesNode.isArray()) {
            notesNode.forEach(note -> notes.add(note.asText()));
        }
        return new WorkflowGenerationResponse(name, description, definition, notes);
    }

    private String requiredText(JsonNode root, String fieldName) {
        String value = root.path(fieldName).asText("");
        if (value.isBlank()) {
            throw new IllegalArgumentException("模型返回缺少必填字段：" + fieldName);
        }
        return value.trim();
    }

    private void validateTemplateVariables(WorkflowDefinition definition) {
        Set<String> nodeIds = definition.nodes().stream()
                .map(WorkflowNode::id)
                .collect(java.util.stream.Collectors.toSet());
        for (WorkflowNode node : definition.nodes()) {
            validateTemplateValue(node.config(), node.id(), nodeIds);
        }
    }

    private void validateTemplateValue(Object value, String ownerNodeId, Set<String> nodeIds) {
        if (value instanceof String text) {
            validateTemplateString(text, ownerNodeId, nodeIds);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> validateTemplateValue(child, ownerNodeId, nodeIds));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(child -> validateTemplateValue(child, ownerNodeId, nodeIds));
        }
    }

    private void validateTemplateString(String text, String ownerNodeId, Set<String> nodeIds) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(text);
        while (matcher.find()) {
            validateTemplateVariable(matcher.group(1), ownerNodeId, nodeIds);
        }
    }

    private void validateTemplateVariable(String variable, String ownerNodeId, Set<String> nodeIds) {
        if (variable.equals("input") || variable.startsWith("input.")
                || variable.equals("context")
                || variable.equals("lastOutput") || variable.startsWith("lastOutput.")
                || variable.equals("toolResult")
                || variable.equals("answer")) {
            return;
        }
        if (variable.startsWith("nodes.")) {
            String[] parts = variable.split("\\.");
            if (parts.length < 3) {
                throw new IllegalArgumentException("节点 " + ownerNodeId + " 使用了不完整模板变量 {{" + variable + "}}");
            }
            if (!nodeIds.contains(parts[1])) {
                throw new IllegalArgumentException("节点 " + ownerNodeId + " 引用了不存在的节点模板变量 {{" + variable + "}}");
            }
            if ("output".equals(parts[2])) {
                throw new IllegalArgumentException("节点 " + ownerNodeId + " 使用了不支持的模板变量 {{" + variable
                        + "}}，LLM 节点回答请使用 {{nodes." + parts[1] + ".answer}}");
            }
            return;
        }
        String prefix = variable.contains(".") ? variable.substring(0, variable.indexOf('.')) : variable;
        if (nodeIds.contains(prefix)) {
            throw new IllegalArgumentException("节点 " + ownerNodeId + " 使用了不支持的模板变量 {{" + variable
                    + "}}，引用节点输出请使用 {{nodes." + prefix + ".answer}}");
        }
        throw new IllegalArgumentException("节点 " + ownerNodeId + " 使用了不支持的模板变量 {{" + variable + "}}");
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
