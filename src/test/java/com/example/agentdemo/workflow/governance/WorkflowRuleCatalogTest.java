package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void detectDomainDoesNotFalsePositiveForGenericBusinessWorkflowWords() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of("summary", "Warehouse shipping return workflow")),
                        new WorkflowNode("decision", "condition",
                                Map.of("left", "{{input.type}}", "operator", "equals", "right", "return")),
                        new WorkflowNode("reply", "llm",
                                Map.of("prompt", "Summarize internal warehouse shipping and return process"))
                ),
                List.of(
                        new WorkflowEdge("start", "decision"),
                        new WorkflowEdge("decision", "reply")
                ));

        assertThat(catalog.detectDomain(definition)).isNull();
    }

    @Test
    void detectDomainActivatesForQueryOrderApiEvenWithoutChineseKeywords() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of("summary", "Check order")),
                        new WorkflowNode("lookup", "tool", Map.of("toolName", "queryOrderAPI")),
                        new WorkflowNode("reply", "llm", Map.of("prompt", "Explain the order result"))
                ),
                List.of(
                        new WorkflowEdge("start", "lookup"),
                        new WorkflowEdge("lookup", "reply")
                ));

        assertThat(catalog.detectDomain(definition)).isEqualTo("customer-service-ecommerce");
    }

    @Test
    void loadedPacksExposeUniqueRuleIdsNonBlankVersionsAndRepairHints() {
        List<WorkflowRulePack> packs = catalog.allPacks();

        assertThat(packs)
                .extracting(WorkflowRulePack::version)
                .allMatch(version -> version != null && !version.isBlank());

        assertThat(packs.stream()
                .flatMap(pack -> pack.rules().stream())
                .map(WorkflowGovernanceRule::id))
                .doesNotHaveDuplicates();

        assertThat(packs.stream()
                .flatMap(pack -> pack.rules().stream())
                .map(WorkflowGovernanceRule::repairHint))
                .allMatch(hint -> hint != null && !hint.isBlank());

        assertThat(packs.stream()
                .flatMap(pack -> pack.rules().stream())
                .map(WorkflowGovernanceRule::antiPatterns))
                .allMatch(entries -> entries != null && !entries.isEmpty());

        assertThat(packs.stream()
                .flatMap(pack -> pack.rules().stream())
                .map(WorkflowGovernanceRule::examples))
                .allMatch(entries -> entries != null && !entries.isEmpty());
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

    @Test
    void rejectsDuplicatePackIds() {
        assertThatThrownBy(() -> new WorkflowRuleCatalog(List.of(corePack(), corePack())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate workflow pack id");
    }

    @Test
    void rejectsDuplicateRuleIdsAcrossPacks() {
        WorkflowRulePack duplicateRulePack = new WorkflowRulePack(
                "other",
                "1.0.0",
                List.of("other"),
                List.of(rule("core-node-types")),
                List.of("entry"),
                List.of(evaluationCase("case-b")));

        assertThatThrownBy(() -> new WorkflowRuleCatalog(List.of(corePack(), duplicateRulePack)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate workflow rule id");
    }

    @Test
    void rejectsDuplicateEvaluationCaseIdsAcrossPacks() {
        WorkflowRulePack duplicateCasePack = new WorkflowRulePack(
                "other",
                "1.0.0",
                List.of("other"),
                List.of(rule("other-rule")),
                List.of("entry"),
                List.of(evaluationCase("core-case")));

        assertThatThrownBy(() -> new WorkflowRuleCatalog(List.of(corePack(), duplicateCasePack)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate workflow evaluation case id");
    }

    @Test
    void rejectsBlankVersions() {
        WorkflowRulePack invalid = new WorkflowRulePack(
                "other",
                "   ",
                List.of("other"),
                List.of(rule("other-rule")),
                List.of("entry"),
                List.of(evaluationCase("other-case")));

        assertThatThrownBy(() -> new WorkflowRuleCatalog(List.of(corePack(), invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workflow rule pack version must not be blank");
    }

    @Test
    void rejectsUnknownSeverities() {
        WorkflowRulePack invalid = new WorkflowRulePack(
                "other",
                "1.0.0",
                List.of("other"),
                List.of(new WorkflowGovernanceRule(
                        "other-rule",
                        "fatal",
                        "Title",
                        "Description",
                        List.of("anti-pattern"),
                        List.of("example"),
                        "repair it",
                        "detector")),
                List.of("entry"),
                List.of(evaluationCase("other-case")));

        assertThatThrownBy(() -> new WorkflowRuleCatalog(List.of(corePack(), invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown workflow rule severity");
    }

    @Test
    void rejectsMissingCorePack() {
        WorkflowRulePack otherPack = new WorkflowRulePack(
                "other",
                "1.0.0",
                List.of("other"),
                List.of(rule("other-rule")),
                List.of("entry"),
                List.of(evaluationCase("other-case")));

        assertThatThrownBy(() -> new WorkflowRuleCatalog(List.of(otherPack)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must include core");
    }

    @Test
    void rejectsBlankRepairHints() {
        WorkflowRulePack invalid = new WorkflowRulePack(
                "other",
                "1.0.0",
                List.of("other"),
                List.of(new WorkflowGovernanceRule(
                        "other-rule",
                        "warning",
                        "Title",
                        "Description",
                        List.of("anti-pattern"),
                        List.of("example"),
                        "   ",
                        "detector")),
                List.of("entry"),
                List.of(evaluationCase("other-case")));

        assertThatThrownBy(() -> new WorkflowRuleCatalog(List.of(corePack(), invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workflow rule repair hint must not be blank");
    }

    @Test
    void rejectsBlankAntiPatternEntries() {
        WorkflowRulePack invalid = new WorkflowRulePack(
                "other",
                "1.0.0",
                List.of("other"),
                List.of(new WorkflowGovernanceRule(
                        "other-rule",
                        "warning",
                        "Title",
                        "Description",
                        List.of("valid", "   "),
                        List.of("example"),
                        "repair",
                        "detector")),
                List.of("entry"),
                List.of(evaluationCase("other-case")));

        assertThatThrownBy(() -> new WorkflowRuleCatalog(List.of(corePack(), invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workflow rule anti-pattern must not be blank");
    }

    @Test
    void rejectsBlankExampleEntries() {
        WorkflowRulePack invalid = new WorkflowRulePack(
                "other",
                "1.0.0",
                List.of("other"),
                List.of(new WorkflowGovernanceRule(
                        "other-rule",
                        "warning",
                        "Title",
                        "Description",
                        List.of("anti-pattern"),
                        List.of("valid", "   "),
                        "repair",
                        "detector")),
                List.of("entry"),
                List.of(evaluationCase("other-case")));

        assertThatThrownBy(() -> new WorkflowRuleCatalog(List.of(corePack(), invalid)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Workflow rule example must not be blank");
    }

    private WorkflowRulePack corePack() {
        return new WorkflowRulePack(
                "core",
                "1.0.0",
                List.of("core"),
                List.of(rule("core-node-types")),
                List.of("entry"),
                List.of(evaluationCase("core-case")));
    }

    private WorkflowGovernanceRule rule(String id) {
        return new WorkflowGovernanceRule(
                id,
                "warning",
                "Title",
                "Description",
                List.of("Do not improvise unsupported nodes."),
                List.of("Use an existing supported node and keep the graph executable."),
                "Repair the workflow by asking for grounded data or restructuring the affected branch.",
                "detector");
    }

    private WorkflowEvaluationCase evaluationCase(String id) {
        return new WorkflowEvaluationCase(id, "Prompt", "Expected behavior");
    }

}
