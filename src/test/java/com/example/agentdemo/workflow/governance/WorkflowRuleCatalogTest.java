package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuleCatalogTest {

    private final WorkflowRuleCatalog catalog = new WorkflowRuleCatalog();

    @Test
    void activePacksReturnsCoreThenCustomerServicePackForCustomerServiceDomain() {
        assertThat(catalog.activePacks("customer-service-ecommerce"))
                .extracting(WorkflowRulePack::id)
                .containsExactly("core", "customer-service-ecommerce");
    }

    @Test
    void activePacksReturnsOnlyCoreForUnrelatedDomain() {
        assertThat(catalog.activePacks("finance"))
                .extracting(WorkflowRulePack::id)
                .containsExactly("core");
    }

    @Test
    void detectDomainRedetectsCustomerServiceSemanticsFromSavedGraph() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of("greeting", "你好，这里是客服工作流")),
                        new WorkflowNode("intent", "llm", Map.of("prompt", "识别订单物流和售后问题")),
                        new WorkflowNode("order_lookup", "tool",
                                Map.of("toolName", "queryOrderAPI", "description", "查询订单物流状态")),
                        new WorkflowNode("reply", "llm", Map.of("prompt", "回复客户订单、物流、退款问题"))
                ),
                List.of(
                        new WorkflowEdge("start", "intent"),
                        new WorkflowEdge("intent", "order_lookup"),
                        new WorkflowEdge("order_lookup", "reply")
                ));

        assertThat(catalog.detectDomain(definition)).isEqualTo("customer-service-ecommerce");
    }

    @Test
    void loadedPacksExposeUniqueRuleIdsAndNonBlankVersions() {
        List<WorkflowRulePack> packs = catalog.allPacks();

        assertThat(packs)
                .extracting(WorkflowRulePack::version)
                .allMatch(version -> version != null && !version.isBlank());

        assertThat(packs.stream()
                .flatMap(pack -> pack.rules().stream())
                .map(WorkflowGovernanceRule::id))
                .doesNotHaveDuplicates();
    }

    @Test
    void customerServicePackIncludesRequiredEvaluationCases() {
        WorkflowRulePack customerServicePack = catalog.allPacks().stream()
                .filter(pack -> "customer-service-ecommerce".equals(pack.id()))
                .findFirst()
                .orElseThrow();

        assertThat(customerServicePack.testCases())
                .extracting(WorkflowEvaluationCase::id)
                .containsExactlyInAnyOrder(
                        "tracking-delay",
                        "missing-order-id-clarification",
                        "urge-shipping",
                        "return-or-exchange",
                        "compound-damage",
                        "vague-complaint",
                        "greeting-only",
                        "missing-order-result-tool-failure");
    }

}
