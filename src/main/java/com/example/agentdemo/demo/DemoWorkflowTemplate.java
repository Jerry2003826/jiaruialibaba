package com.example.agentdemo.demo;

import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowDefinitionSaveRequest;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;

import java.util.List;
import java.util.Map;

final class DemoWorkflowTemplate {

    static final String CUSTOMER_SERVICE_WORKFLOW_NAME = "AI智能客服多分支路由工作流";
    static final String TRAVEL_EXPENSE_CONDITION_WORKFLOW_NAME = "差旅报销多条件判断 Demo";

    private static final String CUSTOMER_SERVICE_WORKFLOW_DESCRIPTION = """
            区分通用政策、商品咨询、具体订单查询和缺少订单号的客服工作流。
            意图节点输出受 JSON Schema 约束；后续条件节点只读取 parsed.intent 做确定性分支。
            """;
    private static final String TRAVEL_EXPENSE_CONDITION_WORKFLOW_DESCRIPTION = """
            使用两个条件节点证明复合条件可在流程中稳定分支。
            第一个节点用 mode=all 判断报销资料是否齐全；第二个节点用 mode=any 判断是否需要人工复核。

            示例输入：
            1. {"message":"上海差旅报销，帮我加急","expenseType":"travel","receiptProvided":true,"amount":1200,"priority":"normal"}
               -> 资料齐全 AND=true，复核 OR=true。
            2. {"message":"上海差旅报销","expenseType":"travel","receiptProvided":true,"amount":380,"priority":"normal"}
               -> 资料齐全 AND=true，复核 OR=false。
            3. {"message":"上海差旅报销","expenseType":"travel","receiptProvided":false,"amount":380,"priority":"normal"}
               -> 资料齐全 AND=false。
            """;

    private DemoWorkflowTemplate() {
    }

    static WorkflowDefinitionSaveRequest customerServiceWorkflowRequest() {
        return new WorkflowDefinitionSaveRequest(CUSTOMER_SERVICE_WORKFLOW_NAME,
                CUSTOMER_SERVICE_WORKFLOW_DESCRIPTION, customerServiceWorkflow());
    }

    static WorkflowDefinitionSaveRequest travelExpenseConditionWorkflowRequest() {
        return new WorkflowDefinitionSaveRequest(TRAVEL_EXPENSE_CONDITION_WORKFLOW_NAME,
                TRAVEL_EXPENSE_CONDITION_WORKFLOW_DESCRIPTION, travelExpenseConditionWorkflow());
    }

    static boolean needsSync(WorkflowDefinition definition) {
        WorkflowNode intentNode = findNode(definition, "llm_intent");
        if (intentNode == null || !"json".equalsIgnoreCase(String.valueOf(intentNode.config().get("outputMode")))
                || !(intentNode.config().get("outputSchema") instanceof Map<?, ?>)) {
            return true;
        }
        return definition.nodes().stream()
                .anyMatch(node -> isLegacyNode(node) || usesLegacyIntentAnswer(node));
    }

    static boolean travelExpenseConditionWorkflowNeedsSync(WorkflowDefinition definition) {
        WorkflowNode complete = findNode(definition, "condition_expense_complete");
        WorkflowNode review = findNode(definition, "condition_manual_review");
        return !isCompositeCondition(complete, "all", 3) || !isCompositeCondition(review, "any", 3)
                || findNode(definition, "tool_missing_info") == null
                || findNode(definition, "tool_manual_review") == null
                || findNode(definition, "tool_auto_approve") == null;
    }

    private static boolean isCompositeCondition(WorkflowNode node, String mode, int expectedConditions) {
        if (node == null || !"condition".equals(node.type())
                || !mode.equalsIgnoreCase(String.valueOf(node.config().get("mode")))) {
            return false;
        }
        Object conditions = node.config().get("conditions");
        if (conditions instanceof List<?> list) {
            return list.size() == expectedConditions;
        }
        return false;
    }

    private static WorkflowDefinition customerServiceWorkflow() {
        return new WorkflowDefinition(List.of(
                node("start", "start", Map.of(), "接收用户消息", "入口"),
                node("llm_intent", "llm", Map.of(
                        "prompt", intentPrompt(),
                        "outputMode", "json",
                        "outputSchema", intentOutputSchema()),
                        "识别意图", "意图识别"),
                condition("condition_is_order_policy", "通用政策判断", "order_policy", "政策流程"),
                node("retriever_policy", "retriever", Map.of(
                        "query", "{{input.message}}",
                        "topK", 5),
                        "检索政策知识", "政策流程"),
                node("llm_policy_answer", "llm", Map.of("prompt", policyAnswerPrompt()),
                        "政策答复", "政策流程"),
                condition("condition_is_product", "商品咨询判断", "product_consult", "商品咨询流程"),
                node("retriever_product", "retriever", Map.of(
                        "query", "{{input.message}}",
                        "topK", 3),
                        "检索商品知识", "商品咨询流程"),
                node("llm_product_answer", "llm", Map.of("prompt", productAnswerPrompt()),
                        "商品答复", "商品咨询流程"),
                condition("condition_is_order_query", "订单查询判断", "order_query", "订单查询流程"),
                node("tool_order_query", "tool", Map.of(
                        "toolName", "queryOrderAPI",
                        "arguments", Map.of("user_query", "{{input.message}}")),
                        "查询订单工具", "订单查询流程"),
                node("llm_order_answer", "llm", Map.of("prompt", orderAnswerPrompt()),
                        "订单答复", "订单查询流程"),
                condition("condition_needs_order_id", "缺少订单号判断", "need_order_id", "补充信息流程"),
                node("llm_ask_order_id", "llm", Map.of("prompt", askOrderIdPrompt()),
                        "索要订单号", "补充信息流程"),
                node("llm_other_handle", "llm", Map.of("prompt", otherIntentPrompt()),
                        "兜底处理", "其他流程"),
                node("end", "end", Map.of(), "结束输出", "出口")),
                List.of(
                        edge("start", "llm_intent", null, "开始识别", "意图识别"),
                        edge("llm_intent", "condition_is_order_policy", null, "读取结构化意图", "意图识别"),
                        edge("condition_is_order_policy", "retriever_policy", "true", "是通用政策", "政策流程"),
                        edge("condition_is_order_policy", "condition_is_product", "false", "不是通用政策", "意图识别"),
                        edge("retriever_policy", "llm_policy_answer", null, "带入知识库", "政策流程"),
                        edge("llm_policy_answer", "end", null, "输出政策答复", "政策流程"),
                        edge("condition_is_product", "retriever_product", "true", "是商品咨询", "商品咨询流程"),
                        edge("condition_is_product", "condition_is_order_query", "false", "不是商品咨询", "意图识别"),
                        edge("retriever_product", "llm_product_answer", null, "带入商品知识", "商品咨询流程"),
                        edge("llm_product_answer", "end", null, "输出商品答复", "商品咨询流程"),
                        edge("condition_is_order_query", "tool_order_query", "true", "有订单号", "订单查询流程"),
                        edge("condition_is_order_query", "condition_needs_order_id", "false", "不是可查订单", "意图识别"),
                        edge("tool_order_query", "llm_order_answer", null, "解释查询结果", "订单查询流程"),
                        edge("llm_order_answer", "end", null, "输出订单答复", "订单查询流程"),
                        edge("condition_needs_order_id", "llm_ask_order_id", "true", "缺少订单号", "补充信息流程"),
                        edge("condition_needs_order_id", "llm_other_handle", "false", "其他意图", "其他流程"),
                        edge("llm_ask_order_id", "end", null, "等待补充", "补充信息流程"),
                        edge("llm_other_handle", "end", null, "输出兜底答复", "其他流程")));
    }

    private static WorkflowDefinition travelExpenseConditionWorkflow() {
        return new WorkflowDefinition(List.of(
                node("start", "start", Map.of(), "接收报销申请", "入口"),
                node("condition_expense_complete", "condition", Map.of(
                        "mode", "all",
                        "conditions", List.of(
                                conditionRule("{{input.expenseType}}", "exists", "", false),
                                conditionRule("{{input.receiptProvided}}", "equals", "true", false),
                                conditionRule("{{input.amount}}", "greaterThan", 0, false))),
                        "资料齐全判断", "AND 多条件"),
                node("condition_manual_review", "condition", Map.of(
                        "mode", "any",
                        "conditions", List.of(
                                conditionRule("{{input.priority}}", "equals", "urgent", false),
                                conditionRule("{{input.message}}", "contains", "加急", false),
                                conditionRule("{{input.amount}}", "greaterThan", 1000, false))),
                        "人工复核判断", "OR 多条件"),
                toolOutcome("tool_missing_info", "资料不全处理", "资料不全",
                        "AND 判断未全部满足：资料或金额字段不完整，需要补齐后再审核。"),
                toolOutcome("tool_manual_review", "人工复核处理", "人工复核",
                        "OR 判断命中至少一条：申请需要人工复核。"),
                toolOutcome("tool_auto_approve", "自动通过处理", "自动通过",
                        "AND 已全部满足，OR 未命中：申请可进入自动通过路径。"),
                node("end", "end", Map.of(), "结束输出", "出口")),
                List.of(
                        edge("start", "condition_expense_complete", null, "开始 AND 判断", "AND 多条件"),
                        edge("condition_expense_complete", "condition_manual_review", "true", "资料齐全", "AND 多条件"),
                        edge("condition_expense_complete", "tool_missing_info", "false", "任一资料缺失", "资料不全"),
                        edge("condition_manual_review", "tool_manual_review", "true", "命中任一复核条件", "OR 多条件"),
                        edge("condition_manual_review", "tool_auto_approve", "false", "未命中复核条件", "自动通过"),
                        edge("tool_missing_info", "end", null, "输出资料补全提示", "资料不全"),
                        edge("tool_manual_review", "end", null, "输出人工复核结论", "人工复核"),
                        edge("tool_auto_approve", "end", null, "输出自动通过结论", "自动通过")));
    }

    private static WorkflowNode condition(String id, String label, String intent, String route) {
        return node(id, "condition", Map.of(
                "left", "{{nodes.llm_intent.parsed.intent}}",
                "operator", "equals",
                "right", intent,
                "caseSensitive", false),
                label, route);
    }

    private static WorkflowNode toolOutcome(String id, String label, String route, String message) {
        return node(id, "tool", Map.of(
                "toolName", "getCurrentTime",
                "arguments", Map.of("message", message),
                "idempotent", true),
                label, route);
    }

    private static Map<String, Object> conditionRule(String left, String operator, Object right, boolean caseSensitive) {
        return Map.of(
                "left", left,
                "operator", operator,
                "right", right,
                "caseSensitive", caseSensitive);
    }

    private static WorkflowNode node(String id, String type, Map<String, Object> config, String label, String route) {
        return new WorkflowNode(id, type, config, label, route);
    }

    private static WorkflowEdge edge(String from, String to, String condition, String label, String route) {
        return new WorkflowEdge(from, to, condition, label, route);
    }

    private static WorkflowNode findNode(WorkflowDefinition definition, String nodeId) {
        return definition.nodes().stream()
                .filter(node -> nodeId.equals(node.id()))
                .findFirst()
                .orElse(null);
    }

    private static boolean isLegacyNode(WorkflowNode node) {
        return "condition_order_lookup_ready".equals(node.id()) || "dynamic_order_query".equals(node.id());
    }

    private static boolean usesLegacyIntentAnswer(WorkflowNode node) {
        Object left = node.config().get("left");
        return left instanceof String text && text.contains("{{nodes.llm_intent.answer}}");
    }

    private static Map<String, Object> intentOutputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("intent", "hasOrderId", "needsOrderId", "orderIds", "confidence"),
                "additionalProperties", false,
                "properties", Map.of(
                        "intent", Map.of(
                                "type", "string",
                                "enum", List.of("order_policy", "order_query", "need_order_id", "product_consult",
                                        "complaint", "human_transfer", "bug_feedback", "sales_lead", "chitchat")),
                        "hasOrderId", Map.of("type", "boolean"),
                        "needsOrderId", Map.of("type", "boolean"),
                        "orderIds", Map.of("type", "array", "items", Map.of("type", "string")),
                        "confidence", Map.of("type", "number")));
    }

    private static String intentPrompt() {
        return """
                你是客服工作流的“意图识别节点”。你不能直接回答用户，只负责把用户消息分类。

                必须只输出一个 JSON 对象，不要 Markdown，不要解释，不要多余文本。JSON 必须符合：
                {
                  "intent": "order_policy | order_query | need_order_id | product_consult | complaint | human_transfer | bug_feedback | sales_lead | chitchat",
                  "hasOrderId": true/false,
                  "needsOrderId": true/false,
                  "orderIds": ["识别到的至少 8 位数字订单号"],
                  "confidence": 0.0 到 1.0
                }

                分类规则：
                - order_policy：通用订单、发货、物流、退货、退款政策或流程问题，且当前消息没有明确订单号。
                - order_query：查询具体订单、物流、退款、退货状态，且当前消息包含至少 8 位数字订单号。
                - need_order_id：查询具体订单、物流、退款、退货状态，但当前消息没有至少 8 位数字订单号。
                - product_consult：商品、产品、功能、使用说明类咨询。
                - complaint：投诉或强烈负面情绪。
                - human_transfer：明确要求人工客服。
                - bug_feedback：反馈系统问题或故障。
                - sales_lead：购买、报价、合作、销售线索。
                - chitchat：其他闲聊。

                用户消息：{{input.message}}
                """;
    }

    private static String policyAnswerPrompt() {
        return """
                你是企业智能客服。请严格基于【知识库内容】回答通用政策/流程问题。

                用户问题：{{input.message}}
                知识库内容：{{context}}

                约束：
                1. 只解释通用流程，不虚构具体订单状态、客户姓名、运单号或退款时间。
                2. 如果知识库有步骤，分点说明。
                3. 如果用户要查具体订单，最后提示其提供完整订单号。
                """;
    }

    private static String productAnswerPrompt() {
        return """
                你是企业智能客服。请严格基于【知识库内容】回答商品/产品咨询。

                用户问题：{{input.message}}
                知识库内容：{{context}}

                约束：
                1. 只能使用知识库明确支持的信息，不要编造。
                2. 如果知识库没有答案，说明当前暂无准确答案，并提供转人工选项。
                3. 回答简洁、清楚、礼貌。
                """;
    }

    private static String orderAnswerPrompt() {
        return """
                你是订单客服助手。根据订单查询结果，用自然语言解释给用户。

                用户问题：{{input.message}}
                订单数据：{{lastOutput}}

                约束：
                1. 不暴露内部字段名和工具名。
                2. 如果查到订单，说明状态、物流、下一步建议。
                3. 如果未查到订单，礼貌提示用户核对完整订单号或转人工。
                """;
    }

    private static String askOrderIdPrompt() {
        return """
                你是客服助手。用户正在询问具体订单、物流、退货或退款状态，但当前消息没有有效完整订单号。

                用户消息：{{input.message}}

                请简洁说明：需要用户提供至少 8 位数字订单号后才能查询。不要编造订单信息。
                """;
    }

    private static String otherIntentPrompt() {
        return """
                你是智能客服。用户输入：{{input.message}}

                当前意图不属于可自动查询的订单场景。请根据情况安抚情绪、提供转人工提示，或进行友好回应。
                """;
    }
}
