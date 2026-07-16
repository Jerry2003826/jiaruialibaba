package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WorkflowPromptDraftService {

    private static final String SYSTEM_PROMPT = """
            你是智能体工作流里“大模型节点”的业务指令撰写助手。
            用户会输入一句很短的业务目标，你要把它扩写成可以直接放入“业务指令”文本框的完整中文文案。

            输出要求：
            - 只输出业务指令正文，不要 Markdown、不要代码块、不要标题、不要解释。
            - 不要包含 {{input.xxx}}、{{state.xxx}}、{{nodes.xxx}} 等模板变量；任务输入会由工作流系统自动接入。
            - 指令必须包含：角色定位、任务目标、判断规则或处理步骤、输出格式、边界情况。
            - 如果目标是分类、判断、路由、审核，要求模型只输出 JSON，并给出稳定字段名和可选枚举值。
            - JSON 字段名和枚举值优先使用英文稳定值，例如 positive、negative、after_sales、shipping。
            - 不要擅自增加用户没有要求的业务分类；如果用户要求“正面还是负面”这类二选一，只保留这两个枚举。
            - 如果用户只写了一个很泛的目标，补齐成保守、可执行、可测试的节点级指令。
            - 语言要适合业务人员阅读，避免技术术语堆砌。
            """;

    private final AiModelService aiModelService;

    public WorkflowPromptDraftService() {
        this(null);
    }

    @Autowired
    public WorkflowPromptDraftService(AiModelService aiModelService) {
        this.aiModelService = aiModelService;
    }

    public WorkflowPromptDraftResponse draft(WorkflowPromptDraftRequest request) {
        if (aiModelService == null) {
            throw new BusinessException("ALIBABA_LLM_NOT_CONFIGURED",
                    "Alibaba LLM is required for workflow prompt drafting");
        }
        String requirement = request.requirement().trim();
        AiModelResult result = aiModelService.generate(SYSTEM_PROMPT, userPrompt(request, requirement));
        StructuredDraft structuredDraft = inferStructuredDraft(requirement, result.answer());
        String instruction = normalizeInstruction(result.answer(), structuredDraft);
        if (!StringUtils.hasText(instruction)) {
            throw new BusinessException("WORKFLOW_PROMPT_DRAFT_EMPTY", "AI did not return a usable prompt draft");
        }
        return new WorkflowPromptDraftResponse(instruction, structuredDraft.outputMode(),
                structuredDraft.outputSchema(), structuredDraft.writeState());
    }

    private String userPrompt(WorkflowPromptDraftRequest request, String requirement) {
        return """
                节点名称：%s
                任务输入：%s
                用户粗略要求：
                %s

                请把“用户粗略要求”扩写为完整业务指令正文。
                """.formatted(blankToDefault(request.nodeLabel(), "大模型节点"),
                blankToDefault(request.inputLabel(), "工作流任务输入"), requirement);
    }

    private String blankToDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private StructuredDraft inferStructuredDraft(String requirement, String answer) {
        String text = (requirement + "\n" + (answer == null ? "" : answer)).toLowerCase(Locale.ROOT);
        if (containsAny(text, "正面", "负面", "好评", "差评", "positive", "negative", "sentiment")) {
            return structuredDraft(List.of(
                    field("sentiment", "评价倾向", "positive 表示正面评论，negative 表示负面评论。",
                            List.of("positive", "negative")),
                    field("reason", "判断原因", "用一句话说明判断依据。", List.of())));
        }
        if (containsAny(text, "售后", "运输", "物流", "after_sales", "shipping")) {
            return structuredDraft(List.of(
                    field("issueType", "问题类型", "after_sales 表示售后问题，shipping 表示运输或物流问题。",
                            List.of("after_sales", "shipping")),
                    field("reason", "判断原因", "用一句话说明判断依据。", List.of())));
        }
        if (containsAny(text, "分类", "判断", "路由", "审核", "classif", "route")) {
            return structuredDraft(List.of(
                    field("result", "判断结果", "模型根据业务规则得到的稳定分类或判断结果。", List.of()),
                    field("reason", "判断原因", "用一句话说明判断依据。", List.of())));
        }
        return new StructuredDraft("text", Map.of(), Map.of());
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private FieldDraft field(String name, String title, String description, List<String> enumValues) {
        return new FieldDraft(name, title, description, enumValues);
    }

    private StructuredDraft structuredDraft(List<FieldDraft> fields) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = fields.stream().map(FieldDraft::name).toList();
        Map<String, Object> writeState = new LinkedHashMap<>();
        for (FieldDraft field : fields) {
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", "string");
            property.put("title", field.title());
            property.put("description", field.description());
            if (!field.enumValues().isEmpty()) {
                property.put("enum", field.enumValues());
            }
            properties.put(field.name(), property);
            writeState.put(field.name(), "{{lastOutput.parsed." + field.name() + "}}");
        }
        Map<String, Object> outputSchema = new LinkedHashMap<>();
        outputSchema.put("type", "object");
        outputSchema.put("required", required);
        outputSchema.put("additionalProperties", false);
        outputSchema.put("properties", properties);
        return new StructuredDraft("json", outputSchema, writeState);
    }

    private String normalizeInstruction(String answer, StructuredDraft structuredDraft) {
        if (!StringUtils.hasText(answer)) {
            return "";
        }
        String text = answer.trim();
        text = text.replaceAll("(?is)^```(?:text|markdown|md)?\\s*", "");
        text = text.replaceAll("(?is)\\s*```$", "");
        text = text.trim();
        String contract = structuredOutputInstruction(structuredDraft);
        if (!StringUtils.hasText(contract) || text.contains("{{lastOutput.parsed")) {
            return text;
        }
        return (text + "\n\n" + contract).trim();
    }

    private String structuredOutputInstruction(StructuredDraft structuredDraft) {
        if (!"json".equals(structuredDraft.outputMode()) || structuredDraft.outputSchema().isEmpty()) {
            return "";
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) structuredDraft.outputSchema().get("properties");
        StringBuilder builder = new StringBuilder("输出结构约束：请只输出一个合法 JSON 对象，不要输出 Markdown、解释或额外文本。");
        for (Map.Entry<String, Object> entry : properties.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> property = (Map<String, Object>) entry.getValue();
            builder.append("\n- ").append(entry.getKey()).append("：").append(property.get("description"));
            Object enumValues = property.get("enum");
            if (enumValues instanceof List<?> values && !values.isEmpty()) {
                builder.append(" 取值只能是 ").append(String.join(" / ",
                        values.stream().map(String::valueOf).toList())).append("。");
            }
        }
        return builder.toString();
    }

    private record FieldDraft(String name, String title, String description, List<String> enumValues) {
    }

    private record StructuredDraft(String outputMode, Map<String, Object> outputSchema,
            Map<String, Object> writeState) {
    }
}
