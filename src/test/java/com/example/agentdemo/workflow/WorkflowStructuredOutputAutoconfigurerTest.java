package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStructuredOutputAutoconfigurerTest {

    private final WorkflowStructuredOutputAutoconfigurer autoconfigurer =
            new WorkflowStructuredOutputAutoconfigurer();

    @Test
    @SuppressWarnings("unchecked")
    void addsCustomerServiceIntentContractToRouterLlmWithoutManualSchema() {
        WorkflowDefinition normalized = autoconfigurer.apply(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm_intent", "llm",
                                Map.of("prompt", "请识别用户意图并决定后续客服流程：{{input.message}}"),
                                "意图识别", "意图识别"),
                        new WorkflowNode("condition_policy", "condition", Map.of(
                                "left", "{{nodes.llm_intent.parsed.intent}}",
                                "operator", "equals",
                                "right", "order_policy")),
                        new WorkflowNode("condition_order", "condition", Map.of(
                                "left", "{{nodes.llm_intent.parsed.intent}}",
                                "operator", "equals",
                                "right", "order_query")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "llm_intent"),
                        new WorkflowEdge("llm_intent", "condition_policy"),
                        new WorkflowEdge("condition_policy", "condition_order", "false"),
                        new WorkflowEdge("condition_order", "end", "true"))));

        WorkflowNode intent = node(normalized, "llm_intent");
        Map<String, Object> config = intent.config();
        Map<String, Object> schema = (Map<String, Object>) config.get("outputSchema");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> intentSchema = (Map<String, Object>) properties.get("intent");

        assertThat(config.get("outputMode")).isEqualTo("json");
        assertThat(config.get("autoStructuredOutputContract")).isEqualTo("customer_service_intent");
        assertThat((String) config.get("prompt"))
                .contains("请识别用户意图并决定后续客服流程")
                .contains("必须只输出一个 JSON 对象");
        assertThat((List<Object>) schema.get("required"))
                .containsExactly("intent", "hasOrderId", "needsOrderId", "orderIds", "confidence");
        assertThat(schema.get("additionalProperties")).isEqualTo(false);
        assertThat(properties)
                .containsKeys("intent", "hasOrderId", "needsOrderId", "orderIds", "confidence");
        assertThat((List<Object>) intentSchema.get("enum"))
                .contains("order_policy", "order_query", "need_order_id", "product_consult");
        assertThat((Map<String, Object>) config.get("writeState"))
                .containsEntry("intent", "{{lastOutput.parsed.intent}}")
                .containsEntry("hasOrderId", "{{lastOutput.parsed.hasOrderId}}")
                .containsEntry("orderIds", "{{lastOutput.parsed.orderIds}}");
    }

    @Test
    void preservesManualStructuredOutputContract() {
        Map<String, Object> manualSchema = Map.of(
                "type", "object",
                "properties", Map.of("sentiment", Map.of("type", "string")));
        WorkflowDefinition normalized = autoconfigurer.apply(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("llm_review", "llm", Map.of(
                                "prompt", "判断情绪",
                                "outputMode", "json",
                                "outputSchema", manualSchema,
                                "writeState", Map.of("sentiment", "{{lastOutput.parsed.sentiment}}")),
                                "情绪判断", null),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("llm_review", "end"))));

        Map<String, Object> config = node(normalized, "llm_review").config();

        assertThat(config.get("outputSchema")).isEqualTo(manualSchema);
        assertThat(config.get("prompt")).isEqualTo("判断情绪");
        assertThat(config).doesNotContainKey("autoStructuredOutputContract");
    }

    @Test
    void doesNotTreatDepartmentNotificationNodeAsCustomerServiceRouterBecauseRouteSaysRouting() {
        WorkflowDefinition normalized = autoconfigurer.apply(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("llm_shipping", "llm", Map.of(
                                "prompt", "这是一条运输相关的负面评价：{{input}}。请生成给运输部门和产品体验部门的通知，说明物流或包装体验问题。"),
                                "生成运输通知", "评价分流"),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("llm_shipping", "end"))));

        Map<String, Object> config = node(normalized, "llm_shipping").config();

        assertThat(config).doesNotContainKeys("outputMode", "outputSchema", "writeState",
                "autoStructuredOutputContract");
        assertThat(config.get("prompt")).asString().doesNotContain("客服意图", "必须只输出一个 JSON 对象");
    }

    @Test
    void keepsFinalTransferLlmInTextModeWhenNoParsedFieldIsConsumedDownstream() {
        Map<String, Object> originalConfig = Map.of(
                "prompt", "你是客服意图判断后的最终转人工客服，请生成客户可读的转接说明。",
                "outputMode", "text");
        WorkflowDefinition normalized = autoconfigurer.apply(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("llm_transfer", "llm", originalConfig, "转人工客服", "客服分流"),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("llm_transfer", "end"))));

        Map<String, Object> config = node(normalized, "llm_transfer").config();

        assertThat(config).isEqualTo(originalConfig);
        assertThat(config).doesNotContainKeys("outputSchema", "writeState", "autoStructuredOutputContract");
    }

    @Test
    void configuresClassifierWhenParsedFieldIsConsumedDownstreamThroughState() {
        WorkflowDefinition normalized = autoconfigurer.apply(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("llm_intent", "llm", Map.of(
                                "prompt", "识别客服订单意图：{{input.message}}",
                                "writeState", Map.of("intent", "{{lastOutput.parsed.intent}}")),
                                "客服意图识别", null),
                        new WorkflowNode("condition_order", "condition", Map.of(
                                "left", "{{state.intent}}",
                                "operator", "equals",
                                "right", "order_query")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("llm_intent", "condition_order"),
                        new WorkflowEdge("condition_order", "end", "true"))));

        Map<String, Object> config = node(normalized, "llm_intent").config();

        assertThat(config)
                .containsEntry("outputMode", "json")
                .containsEntry("autoStructuredOutputContract", "customer_service_intent")
                .containsKeys("outputSchema", "writeState");
    }

    @Test
    void doesNotInstallIntentContractForDirectNonIntentParsedConsumer() {
        Map<String, Object> originalConfig = Map.of(
                "prompt", "判断客服评价情绪：{{input.message}}");
        WorkflowDefinition normalized = autoconfigurer.apply(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("llm_sentiment", "llm", originalConfig, "客服情绪判断", null),
                        new WorkflowNode("condition_sentiment", "condition", Map.of(
                                "left", "{{nodes.llm_sentiment.parsed.sentiment}}",
                                "operator", "equals",
                                "right", "negative")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("llm_sentiment", "condition_sentiment"),
                        new WorkflowEdge("condition_sentiment", "end", "true"))));

        assertThat(node(normalized, "llm_sentiment").config()).isEqualTo(originalConfig);
    }

    @Test
    void doesNotInstallIntentContractForStateBackedNonIntentParsedConsumer() {
        Map<String, Object> originalConfig = Map.of(
                "prompt", "判断客服评价情绪：{{input.message}}",
                "writeState", Map.of("sentiment", "{{lastOutput.parsed.sentiment}}"));
        WorkflowDefinition normalized = autoconfigurer.apply(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("llm_sentiment", "llm", originalConfig, "客服情绪判断", null),
                        new WorkflowNode("condition_sentiment", "condition", Map.of(
                                "left", "{{state.sentiment}}",
                                "operator", "equals",
                                "right", "negative")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("llm_sentiment", "condition_sentiment"),
                        new WorkflowEdge("condition_sentiment", "end", "true"))));

        assertThat(node(normalized, "llm_sentiment").config()).isEqualTo(originalConfig);
    }

    @Test
    void preservesLegacyManualSchemaOnFinalTransferLlmWithoutParsedConsumers() {
        Map<String, Object> manualSchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "confidence", Map.of("type", "number"),
                        "intent", Map.of("type", "string"),
                        "needsOrderId", Map.of("type", "boolean"),
                        "hasOrderId", Map.of("type", "boolean"),
                        "orderIds", Map.of("type", "array")));
        Map<String, Object> originalConfig = Map.of(
                "prompt", "判断客服请求后生成最终转人工结果。",
                "outputMode", "json",
                "outputSchema", manualSchema);
        WorkflowDefinition normalized = autoconfigurer.apply(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("llm_transfer", "llm", originalConfig, "转人工客服", null),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("llm_transfer", "end"))));

        Map<String, Object> config = node(normalized, "llm_transfer").config();

        assertThat(config).isEqualTo(originalConfig);
        assertThat(config).doesNotContainKey("autoStructuredOutputContract");
    }

    @Test
    @SuppressWarnings("unchecked")
    void reclaimsLegacyCustomerServiceIntentSchemaAsAutomaticContract() {
        Map<String, Object> legacySchema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "confidence", Map.of("type", "number"),
                        "intent", Map.of("type", "string"),
                        "needsOrderId", Map.of("type", "boolean"),
                        "hasOrderId", Map.of("type", "boolean"),
                        "orderIds", Map.of("type", "array")));
        WorkflowDefinition normalized = autoconfigurer.apply(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("llm_intent", "llm", Map.of(
                                "prompt", "识别客服意图：{{input.message}}",
                                "outputMode", "json",
                                "outputSchema", legacySchema),
                                "意图识别", null),
                        new WorkflowNode("condition_order", "condition", Map.of(
                                "left", "{{nodes.llm_intent.parsed.intent}}",
                                "operator", "equals",
                                "right", "order_query"))),
                List.of(new WorkflowEdge("llm_intent", "condition_order"))));

        Map<String, Object> config = node(normalized, "llm_intent").config();
        Map<String, Object> schema = (Map<String, Object>) config.get("outputSchema");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(config.get("autoStructuredOutputContract")).isEqualTo("customer_service_intent");
        assertThat((String) config.get("prompt")).contains("必须只输出一个 JSON 对象");
        assertThat(properties).containsKeys("intent", "hasOrderId", "needsOrderId", "orderIds", "confidence");
        assertThat((Map<String, Object>) config.get("writeState"))
                .containsEntry("intent", "{{lastOutput.parsed.intent}}")
                .containsEntry("confidence", "{{lastOutput.parsed.confidence}}");
    }

    private WorkflowNode node(WorkflowDefinition definition, String id) {
        return definition.nodes().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

}
