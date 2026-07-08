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

    private WorkflowNode node(WorkflowDefinition definition, String id) {
        return definition.nodes().stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

}
