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
    void detectDomainRespectsExplicitEcommerceExclusion() {
        assertThat(catalog.detectDomainText(
                "这是通用知识库问答，不是电商客服。禁止加入订单、退货、退款、物流或售后业务。"))
                .isNull();
        assertThat(catalog.detectDomainText(
                "This is not an e-commerce customer-service workflow; do not add order or refund handling."))
                .isNull();
    }

    @Test
    void detectDomainDoesNotTreatGenericCustomerAudienceAsEcommerceSupport() {
        assertThat(catalog.detectDomainText("最终读者是内部战略团队还是外部客户高管？"))
                .isNull();
        assertThat(catalog.detectDomainText("研究目标客户、竞品和市场空间。"))
                .isNull();
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
    void detectDomainActivatesForIndependentEnglishShippingAndReturnSignals() {
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

        assertThat(catalog.detectDomain(definition)).isEqualTo("customer-service-ecommerce");
    }

    @Test
    void detectDomainActivatesForEachChineseAndEnglishGraphSignalAndAllowedToolName() {
        List<String> signals = List.of(
                "客服", "售后", "投诉", "工单",
                "订单", "物流", "退款", "退货", "换货", "发货", "配送", "补发", "商品", "破损", "会员",
                "CRM", "VIP",
                "order", "logistics", "delivery", "refund", "return", "exchange", "shipping", "shipment",
                "damaged product", "membership",
                "customer service", "support ticket",
                "queryCustomerCRM", "lookupShipmentTracking", "createRefundRequest");

        assertThat(signals).allSatisfy(signal -> {
            WorkflowDefinition definition = new WorkflowDefinition(
                    List.of(
                            new WorkflowNode("start", "start", Map.of()),
                            new WorkflowNode("dynamic", "dynamic", Map.of(
                                    "itemsFrom", "{{input.tools}}",
                                    "allowedTools", List.of(signal),
                                    "summary", signal)),
                            new WorkflowNode("end", "end", Map.of())),
                    List.of(new WorkflowEdge("start", "dynamic"), new WorkflowEdge("dynamic", "end")));

            assertThat(catalog.detectDomain(definition))
                    .as("graph signal %s", signal)
                    .isEqualTo("customer-service-ecommerce");
        });
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
                .containsExactly(
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
    void coreCasesAreStaticAndCustomerServiceCasesAreStructuredRuntimeCases() {
        WorkflowRulePack core = catalog.activePacks(null).getFirst();
        WorkflowRulePack customerService = catalog.activePacks("customer-service-ecommerce").get(1);

        assertThat(core.testCases())
                .extracting(WorkflowEvaluationCase::kind)
                .containsOnly(WorkflowEvaluationCaseKind.STATIC);
        assertThat(customerService.testCases()).hasSize(8).allSatisfy(workflowCase -> {
            assertThat(workflowCase.kind()).isEqualTo(WorkflowEvaluationCaseKind.RUNTIME);
            assertThat(workflowCase.runtimeInput()).containsKey("message");
            assertThat(workflowCase.assertions()).isNotEmpty();
        });

        assertThat(customerService.testCases().stream()
                .flatMap(workflowCase -> workflowCase.assertions().stream())
                .map(WorkflowEvaluationAssertion::type))
                .contains(
                        WorkflowEvaluationAssertionType.PATH_NODE_TYPES_INCLUDE_IN_ORDER,
                        WorkflowEvaluationAssertionType.PATH_NODE_TYPES_EXCLUDE,
                        WorkflowEvaluationAssertionType.TOOL_SUCCEEDED,
                        WorkflowEvaluationAssertionType.TOOL_OUTPUT_FIELD_EQUALS,
                        WorkflowEvaluationAssertionType.PARSED_FIELDS_INCLUDE,
                        WorkflowEvaluationAssertionType.FINAL_OUTPUT_CUSTOMER_READABLE,
                        WorkflowEvaluationAssertionType.FINAL_OUTPUT_EXCLUDES);
    }

    @Test
    void successLookupCasesUseRealSeededDemoOrderIds() {
        WorkflowRulePack customerService = catalog.activePacks("customer-service-ecommerce").get(1);
        Map<String, String> promptsById = customerService.testCases().stream()
                .collect(java.util.stream.Collectors.toMap(
                        WorkflowEvaluationCase::id,
                        workflowCase -> String.valueOf(workflowCase.runtimeInput().get("message"))));

        assertThat(promptsById.get("tracking-delay")).contains("20260630001");
        assertThat(promptsById.get("urge-shipping")).contains("20260630003");
        assertThat(promptsById.get("return-or-exchange")).contains("20260630002");
        assertThat(promptsById.get("missing-order-result-tool-failure")).contains("99999999999");
        WorkflowEvaluationCase forcedFailure = customerService.testCases().stream()
                .filter(workflowCase -> workflowCase.id().equals("urge-shipping"))
                .findFirst()
                .orElseThrow();
        assertThat(forcedFailure.fixture()).isNotNull();
        assertThat(forcedFailure.fixture().failTool()).isEqualTo("queryOrderAPI");
        assertThat(forcedFailure.assertions())
                .extracting(WorkflowEvaluationAssertion::type)
                .contains(WorkflowEvaluationAssertionType.TOOL_FAILED);
    }

    @Test
    void legacyThreeArgumentEvaluationCaseConstructorRemainsStaticCompatible() {
        WorkflowEvaluationCase workflowCase = new WorkflowEvaluationCase("legacy", "Prompt", "Expected");

        assertThat(workflowCase.kind()).isEqualTo(WorkflowEvaluationCaseKind.STATIC);
        assertThat(workflowCase.runtimeInput()).isEmpty();
        assertThat(workflowCase.assertions()).isEmpty();
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
