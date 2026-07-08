package com.example.agentdemo.workflow;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
class WorkflowStructuredOutputAutoconfigurer {

    private static final String CUSTOMER_SERVICE_INTENT_CONTRACT = "customer_service_intent";
    private static final List<String> DEFAULT_INTENTS = List.of(
            "order_policy",
            "order_query",
            "need_order_id",
            "product_consult",
            "complaint",
            "human_transfer",
            "bug_feedback",
            "sales_lead",
            "chitchat");

    WorkflowDefinition apply(WorkflowDefinition definition) {
        List<WorkflowNode> nodes = new ArrayList<>();
        boolean changed = false;
        for (WorkflowNode node : definition.nodes()) {
            WorkflowNode normalized = maybeApplyContract(definition, node);
            nodes.add(normalized);
            changed = changed || normalized != node;
        }
        return changed ? new WorkflowDefinition(nodes, definition.edges()) : definition;
    }

    private WorkflowNode maybeApplyContract(WorkflowDefinition definition, WorkflowNode node) {
        if (!"llm".equalsIgnoreCase(node.type()) || hasManualOutputSchema(node.config())) {
            return node;
        }
        Set<String> intentValues = conditionValuesFor(definition, node.id(), "intent");
        if (!isCustomerServiceIntentNode(node, intentValues)) {
            return node;
        }
        Map<String, Object> config = new LinkedHashMap<>(node.config());
        config.put("outputMode", "json");
        config.put("outputSchema", customerServiceIntentSchema(intentValues));
        config.put("writeState", mergedWriteState(config.get("writeState")));
        config.put("prompt", promptWithStructuredOutputInstruction(String.valueOf(config.getOrDefault("prompt", ""))));
        config.put("autoStructuredOutputContract", CUSTOMER_SERVICE_INTENT_CONTRACT);
        return new WorkflowNode(node.id(), node.type(), config, node.label(), node.route());
    }

    private boolean hasManualOutputSchema(Map<String, Object> config) {
        Object schema = config.get("outputSchema");
        return schema instanceof Map<?, ?> map && !map.isEmpty()
                && !CUSTOMER_SERVICE_INTENT_CONTRACT.equals(config.get("autoStructuredOutputContract"))
                && !isCustomerServiceIntentSchema(map);
    }

    private boolean isCustomerServiceIntentSchema(Map<?, ?> schema) {
        Object properties = schema.get("properties");
        if (!(properties instanceof Map<?, ?> propertyMap)) {
            return false;
        }
        Set<String> fieldNames = new LinkedHashSet<>();
        propertyMap.keySet().forEach(key -> fieldNames.add(String.valueOf(key)));
        long matchedFields = List.of("intent", "hasOrderId", "needsOrderId", "orderIds", "confidence").stream()
                .filter(fieldNames::contains)
                .count();
        return fieldNames.contains("intent") && fieldNames.contains("confidence") && matchedFields >= 4;
    }

    private boolean isCustomerServiceIntentNode(WorkflowNode node, Set<String> intentValues) {
        if (intentValues.stream().anyMatch(DEFAULT_INTENTS::contains)) {
            return true;
        }
        String text = String.join(" ",
                nullToEmpty(node.id()),
                nullToEmpty(node.label()),
                nullToEmpty(node.route()),
                String.valueOf(node.config().getOrDefault("prompt", ""))).toLowerCase(Locale.ROOT);
        boolean router = containsAny(text, "意图", "intent", "路由", "分流", "分类", "判断", "route", "classif");
        boolean service = containsAny(text, "客服", "订单", "商品", "政策", "物流", "退款", "退货",
                "customer", "order", "product", "policy");
        return router && service;
    }

    private Set<String> conditionValuesFor(WorkflowDefinition definition, String nodeId, String fieldName) {
        Set<String> values = new LinkedHashSet<>();
        Pattern referencePattern = Pattern.compile("\\{\\{\\s*nodes\\."
                + Pattern.quote(nodeId)
                + "\\.parsed\\."
                + Pattern.quote(fieldName)
                + "\\s*}}");
        for (WorkflowNode node : definition.nodes()) {
            collectConditionValues(node.config(), referencePattern, values);
        }
        return values;
    }

    private void collectConditionValues(Object value, Pattern referencePattern, Set<String> values) {
        if (value instanceof Map<?, ?> map) {
            Object left = map.get("left");
            Object right = map.get("right");
            if (left instanceof String text && referencePattern.matcher(text).find() && right instanceof String rightText
                    && StringUtils.hasText(rightText)) {
                values.add(rightText.trim());
            }
            map.values().forEach(child -> collectConditionValues(child, referencePattern, values));
        }
        else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(child -> collectConditionValues(child, referencePattern, values));
        }
    }

    private Map<String, Object> customerServiceIntentSchema(Set<String> discoveredIntents) {
        List<String> enumValues = new ArrayList<>(DEFAULT_INTENTS);
        discoveredIntents.stream()
                .filter(StringUtils::hasText)
                .filter(value -> !enumValues.contains(value))
                .forEach(enumValues::add);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("intent", mapOf(
                "type", "string",
                "title", "意图",
                "enum", enumValues));
        properties.put("hasOrderId", mapOf(
                "type", "boolean",
                "title", "是否已有订单号"));
        properties.put("needsOrderId", mapOf(
                "type", "boolean",
                "title", "是否需要补充订单号"));
        properties.put("orderIds", mapOf(
                "type", "array",
                "title", "订单号列表",
                "items", Map.of("type", "string")));
        properties.put("confidence", mapOf(
                "type", "number",
                "title", "置信度"));

        return mapOf(
                "type", "object",
                "required", List.of("intent", "hasOrderId", "needsOrderId", "orderIds", "confidence"),
                "additionalProperties", false,
                "properties", properties);
    }

    private Map<String, Object> mergedWriteState(Object existing) {
        Map<String, Object> writeState = new LinkedHashMap<>();
        writeState.put("intent", "{{lastOutput.parsed.intent}}");
        writeState.put("hasOrderId", "{{lastOutput.parsed.hasOrderId}}");
        writeState.put("needsOrderId", "{{lastOutput.parsed.needsOrderId}}");
        writeState.put("orderIds", "{{lastOutput.parsed.orderIds}}");
        writeState.put("confidence", "{{lastOutput.parsed.confidence}}");
        if (existing instanceof Map<?, ?> existingMap) {
            existingMap.forEach((key, value) -> writeState.put(String.valueOf(key), value));
        }
        return writeState;
    }

    private String promptWithStructuredOutputInstruction(String prompt) {
        String base = StringUtils.hasText(prompt) ? prompt.trim() : "请根据用户消息识别客服意图：{{input.message}}";
        if (base.contains("必须只输出一个 JSON 对象")) {
            return base;
        }
        return base + """


                结构化输出要求：
                必须只输出一个 JSON 对象，不要 Markdown，不要解释，不要多余文本。JSON 字段必须为：
                {
                  "intent": "order_policy | order_query | need_order_id | product_consult | complaint | human_transfer | bug_feedback | sales_lead | chitchat",
                  "hasOrderId": true/false,
                  "needsOrderId": true/false,
                  "orderIds": ["识别到的订单号，没有则为空数组"],
                  "confidence": 0.0 到 1.0
                }
                """;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Map<String, Object> mapOf(Object... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return map;
    }

}
