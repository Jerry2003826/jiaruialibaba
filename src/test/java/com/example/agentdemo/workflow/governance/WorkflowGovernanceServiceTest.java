package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.workflow.WorkflowCompiler;
import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;
import com.example.agentdemo.workflow.WorkflowNodeSchemaRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowGovernanceServiceTest {

    private final WorkflowRuleCatalog ruleCatalog = new WorkflowRuleCatalog();
    private final WorkflowNodeSchemaRegistry schemaRegistry = new WorkflowNodeSchemaRegistry();
    private final ToolGatewayService toolGatewayService = mock(ToolGatewayService.class);
    private WorkflowGovernanceService governanceService;

    @BeforeEach
    void setUp() {
        when(toolGatewayService.listExecutableTools()).thenReturn(executableTools());
        governanceService = new WorkflowGovernanceService(
                ruleCatalog,
                schemaRegistry,
                toolGatewayService,
                new WorkflowCompiler(schemaRegistry));
    }

    @Test
    void reportsCompilerBackedFindingForUnregisteredNodeType() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        node("crm_magic", "crm_lookup"),
                        node("end", "end")),
                List.of(edge("start", "crm_magic"), edge("crm_magic", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(definition, context("core", ""));

        WorkflowGovernanceFinding finding = finding(report, "core-registered-node-types");
        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.BLOCK);
        assertThat(finding.phase()).isEqualTo(WorkflowGovernanceFinding.Phase.STATIC);
        assertThat(finding.nodeIds()).containsExactly("crm_magic");
        assertThat(finding.evidence().toString()).contains("crm_lookup");
    }

    @Test
    void reportsCompilerBackedFindingWhenUnsupportedNodeTypeIsNull() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("missing_type", null, Map.of()),
                        node("end", "end")),
                List.of(edge("start", "missing_type"), edge("missing_type", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "")),
                "core-registered-node-types");

        assertThat(finding.nodeIds()).containsExactly("missing_type");
        assertThat(finding.evidence().toString()).contains("null");
    }

    @Test
    void preservesAuthoritativeCompilerFailureWhenAnotherUnsupportedNodeAlsoExists() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of("unsupportedConfig", true)),
                        node("crm_magic", "crm_lookup"),
                        node("end", "end")),
                List.of(edge("start", "crm_magic"), edge("crm_magic", "end")));

        assertThatThrownBy(() -> governanceService.evaluateStatic(definition, context("core", "")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported config key for node start: unsupportedConfig");
    }

    @Test
    void reportsUnregisteredToolNames() {
        WorkflowDefinition definition = linear(
                new WorkflowNode("tool_crm", "tool", Map.of("toolName", "unregisteredCustomerCRM")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "")),
                "core-registered-tools");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.BLOCK);
        assertThat(finding.nodeIds()).containsExactly("tool_crm");
        assertThat(finding.evidence().toString()).contains("unregisteredCustomerCRM");
    }

    @Test
    void reportsToolNodeWithMissingToolName() {
        WorkflowDefinition definition = linear(new WorkflowNode("tool_missing", "tool", Map.of()));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "Call an explicitly selected tool.")),
                "core-registered-tools");

        assertThat(finding.nodeIds()).containsExactly("tool_missing");
        assertThat(finding.evidence().toString()).contains("missingToolNameNodes", "tool_missing");
    }

    @Test
    void reportsGetCurrentTimeUsedAsCrmAndLogisticsLookup() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_fake_lookup", "tool", Map.of("toolName", "getCurrentTime"),
                                "查询 CRM VIP 和订单物流", "客服订单"),
                        new WorkflowNode("llm_reply", "llm",
                                Map.of("prompt", "把上一步当作 CRM VIP 与物流查询结果回复客户：{{lastOutput}}")),
                        node("end", "end")),
                List.of(
                        edge("start", "tool_fake_lookup"),
                        edge("tool_fake_lookup", "llm_reply"),
                        edge("llm_reply", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("customer-service-ecommerce", "客服订单物流与 VIP 查询"));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .contains("cs-authoritative-crm-vip-data", "cs-real-order-and-logistics-lookup");
        assertThat(finding(report, "cs-real-order-and-logistics-lookup").evidence().toString())
                .contains("getCurrentTime");
    }

    @Test
    void doesNotTreatIndependentTimeNodeAsLogisticsLookup() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_time", "tool", Map.of("toolName", "getCurrentTime"),
                                "记录处理时间", null),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("llm_reply", "llm",
                                Map.of("prompt", "根据订单查询结果回复客户：{{lastOutput}}")),
                        node("end", "end")),
                List.of(
                        edge("start", "tool_time"),
                        edge("tool_time", "tool_order"),
                        edge("tool_order", "llm_reply"),
                        edge("llm_reply", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("customer-service-ecommerce", "记录时间后查询订单物流"));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("cs-real-order-and-logistics-lookup", "cs-authoritative-crm-vip-data");
    }

    @Test
    void doesNotTreatAuditTimestampAsCrmDataWhenConsumerSeparatelyRequiresCrmHandoff() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_time", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("llm_audit", "llm", Map.of(
                                "prompt", "Record audit timestamp {{lastOutput}}. CRM requests must transfer to a human; "
                                        + "do not query membership.")),
                        node("end", "end")),
                List.of(edge("start", "tool_time"), edge("tool_time", "llm_audit"), edge("llm_audit", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("customer-service-ecommerce", "Record an audit time and hand CRM requests to a human."));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("cs-authoritative-crm-vip-data", "cs-real-order-and-logistics-lookup");
    }

    @Test
    void doesNotTreatAuditTimestampAsOrderDataBecauseToolRouteNamesAnOrderFlow() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_time", "tool", Map.of("toolName", "getCurrentTime"),
                                "记录审计时间", "订单流程"),
                        new WorkflowNode("llm_audit", "llm", Map.of(
                                "prompt", "Record audit timestamp {{lastOutput}}.")),
                        node("end", "end")),
                List.of(edge("start", "tool_time"), edge("tool_time", "llm_audit"), edge("llm_audit", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("customer-service-ecommerce", "Record an audit timestamp before continuing."));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("cs-authoritative-crm-vip-data", "cs-real-order-and-logistics-lookup");
    }

    @Test
    void reportsConditionFieldMissingFromUpstreamStructuredOutput() {
        WorkflowDefinition definition = classifiedBranch(
                "判断意图并只输出 {\"intent\":\"positive\"}",
                schema(Map.of("intent", Map.of("type", "string"))),
                "{{nodes.llm_classify.parsed.sentiment}}",
                "positive");

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "情绪分类")),
                "core-output-contract-consistency");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.WARN);
        assertThat(finding.nodeIds()).contains("llm_classify", "condition_route");
        assertThat(finding.evidence().toString()).contains("sentiment", "llm_classify");
    }

    @Test
    void reportsPromptAndSchemaFieldConflict() {
        WorkflowDefinition definition = classifiedBranch(
                "只输出合法 JSON：{\"sentiment\":\"positive\",\"reason\":\"...\"}",
                schema(Map.of("intent", Map.of("type", "string"))),
                "{{nodes.llm_classify.parsed.intent}}",
                "positive");

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "情绪分类")),
                "core-output-contract-consistency");

        assertThat(finding.evidence().toString()).contains("sentiment", "reason", "intent");
    }

    @Test
    void acceptsNestedPromptFieldsDeclaredInsideNestedSchemaProperties() {
        WorkflowDefinition definition = linear(new WorkflowNode("llm_parse", "llm", Map.of(
                "prompt", "只输出合法 JSON：{\"subtopics\":[{\"title\":\"...\",\"keywords\":[\"...\"]}]}",
                "outputMode", "json",
                "outputSchema", schema(Map.of(
                        "subtopics", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "title", Map.of("type", "string"),
                                                "keywords", Map.of(
                                                        "type", "array",
                                                        "items", Map.of("type", "string"))))))))));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("core", "把研究主题拆成带标题和关键词的子课题。"));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("core-output-contract-consistency");
    }

    @Test
    void blocksSimulatedExternalSearchWithoutAnExecutableSearchTool() {
        WorkflowDefinition definition = linear(new WorkflowNode("llm_fake_search", "llm", Map.of(
                "prompt", "当前系统未接入外部 Web Search API，请基于内置知识模拟搜索，"
                        + "执行两轮关键词重试并输出多源搜索结果。")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "创建深度研究工作流")),
                "core-unsupported-business-claims");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.BLOCK);
        assertThat(finding.nodeIds()).containsExactly("llm_fake_search");
        assertThat(finding.evidence().toString()).contains("simulated-external-search", "模拟搜索");
    }

    @Test
    void acceptsExternalSearchClaimsGroundedByAConsumedTavilyNode() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("web_search", "tavily_search", Map.of(
                                "query", "{{input.message}}",
                                "searchDepth", "advanced",
                                "maxResults", 5)),
                        new WorkflowNode("llm_report", "llm", Map.of(
                                "prompt", "根据 Tavily 搜索结果 {{lastOutput.results}} 生成报告，不得模拟搜索。")),
                        node("end", "end")),
                List.of(
                        edge("start", "web_search"),
                        edge("web_search", "llm_report"),
                        edge("llm_report", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("core", "联网搜索并生成带来源的研究报告"));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("core-unsupported-business-claims");
    }

    @Test
    void blocksAPathWithMoreThanTwoSerialLlmCalls() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("llm_parse", "llm", Map.of("prompt", "拆解主题")),
                        new WorkflowNode("llm_research", "llm", Map.of("prompt", "研究主题")),
                        new WorkflowNode("llm_report", "llm", Map.of("prompt", "生成报告")),
                        node("end", "end")),
                List.of(
                        edge("start", "llm_parse"),
                        edge("llm_parse", "llm_research"),
                        edge("llm_research", "llm_report"),
                        edge("llm_report", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "创建深度研究工作流")),
                "core-runtime-budget-fit");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.BLOCK);
        assertThat(finding.nodeIds()).containsExactlyInAnyOrder("llm_parse", "llm_research", "llm_report");
        assertThat(finding.evidence()).containsEntry("maximumSerialLlmCalls", 3);
    }

    @Test
    void reportsMissingStructuredFieldConsumedByDownstreamLlmPrompt() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("llm_source", "llm", Map.of(
                                "prompt", "输出意图 JSON",
                                "outputMode", "json",
                                "outputSchema", schema(Map.of("intent", Map.of("type", "string"))))),
                        new WorkflowNode("llm_consumer", "llm",
                                Map.of("prompt", "根据情绪回答：{{nodes.llm_source.parsed.sentiment}}")),
                        node("end", "end")),
                List.of(edge("start", "llm_source"), edge("llm_source", "llm_consumer"),
                        edge("llm_consumer", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "结构化输出")),
                "core-output-contract-consistency");

        assertThat(finding.nodeIds()).contains("llm_source", "llm_consumer");
        assertThat(finding.evidence().toString()).contains("sentiment", "llm_consumer");
    }

    @Test
    void reportsLastOutputParsedFieldMissingFromUniqueDirectPredecessorSchema() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("llm_source", "llm", Map.of(
                                "prompt", "Classify intent as JSON.",
                                "outputMode", "json",
                                "outputSchema", schema(Map.of("intent", Map.of("type", "string"))))),
                        new WorkflowNode("condition_route", "condition", Map.of(
                                "left", "{{lastOutput.parsed.sentiment}}",
                                "operator", "equals",
                                "right", "negative")),
                        new WorkflowNode("llm_true", "llm", Map.of("prompt", "Negative reply")),
                        new WorkflowNode("llm_false", "llm", Map.of("prompt", "Other reply")),
                        node("end", "end")),
                List.of(
                        edge("start", "llm_source"),
                        edge("llm_source", "condition_route"),
                        new WorkflowEdge("condition_route", "llm_true", "true"),
                        new WorkflowEdge("condition_route", "llm_false", "false"),
                        edge("llm_true", "end"),
                        edge("llm_false", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "Structured routing")),
                "core-output-contract-consistency");

        assertThat(finding.evidence().toString())
                .contains("lastOutput.parsed.sentiment", "llm_source", "condition_route");
    }

    @Test
    void reportsStateConsumerWithoutReachableUpstreamWriteState() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("llm_source", "llm", Map.of(
                                "prompt", "输出意图 JSON",
                                "outputMode", "json",
                                "outputSchema", schema(Map.of("intent", Map.of("type", "string"))))),
                        new WorkflowNode("condition_route", "condition", Map.of(
                                "left", "{{state.sentiment}}",
                                "operator", "equals",
                                "right", "negative")),
                        new WorkflowNode("llm_true", "llm", Map.of("prompt", "负面回复")),
                        new WorkflowNode("llm_false", "llm", Map.of("prompt", "其他回复")),
                        node("end", "end")),
                List.of(
                        edge("start", "llm_source"),
                        edge("llm_source", "condition_route"),
                        new WorkflowEdge("condition_route", "llm_true", "true"),
                        new WorkflowEdge("condition_route", "llm_false", "false"),
                        edge("llm_true", "end"),
                        edge("llm_false", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "状态路由")),
                "core-output-contract-consistency");

        assertThat(finding.nodeIds()).contains("condition_route");
        assertThat(finding.evidence().toString()).contains("state.sentiment", "missing-upstream-state-write");
    }

    @Test
    void reportsStateWriterThatDoesNotDominateEveryPathToConsumer() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("condition_route", "condition", Map.of(
                                "left", "{{input.mode}}",
                                "operator", "equals",
                                "right", "write")),
                        new WorkflowNode("llm_writer", "llm", Map.of(
                                "prompt", "Write route state",
                                "writeState", Map.of("route", "written"))),
                        new WorkflowNode("end", "end", Map.of(
                                "writeState", Map.of("result", "{{state.route}}")))),
                List.of(
                        edge("start", "condition_route"),
                        new WorkflowEdge("condition_route", "llm_writer", "true"),
                        new WorkflowEdge("condition_route", "end", "false"),
                        edge("llm_writer", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "State must exist on every path")),
                "core-output-contract-consistency");

        assertThat(finding.evidence().toString())
                .contains("state.route", "state-writer-does-not-dominate-consumer", "llm_writer", "end");
    }

    @Test
    void acceptsOwnWriteStateLastOutputParsedFieldDeclaredByTheSameLlmNode() {
        WorkflowDefinition definition = linear(new WorkflowNode("llm_classify", "llm", Map.of(
                "prompt", "Classify the intent.",
                "outputMode", "json",
                "outputSchema", schema(Map.of("intent", Map.of("type", "string"))),
                "writeState", Map.of("intent", "{{lastOutput.parsed.intent}}"))));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("core", "Classify and persist the intent."));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("core-output-contract-consistency");
    }

    @Test
    void reportsOwnWriteStateLastOutputParsedFieldAgainstTheSameLlmSchema() {
        WorkflowDefinition definition = linear(new WorkflowNode("llm_classify", "llm", Map.of(
                "prompt", "Classify the intent.",
                "outputMode", "json",
                "outputSchema", schema(Map.of("intent", Map.of("type", "string"))),
                "writeState", Map.of("sentiment", "{{lastOutput.parsed.sentiment}}"))));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "Persist structured output.")),
                "core-output-contract-consistency");

        assertThat(finding.evidence().toString())
                .contains("missing-write-state-source-field", "llm_classify", "sentiment");
    }

    @Test
    void reportsCustomerFacingRawJson() {
        WorkflowDefinition definition = linear(new WorkflowNode(
                "llm_final",
                "llm",
                Map.of(
                        "prompt", "只输出合法 JSON，不要解释",
                        "outputMode", "json",
                        "outputSchema", schema(Map.of("answer", Map.of("type", "string"))))));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "给客户自然语言答复")),
                "core-customer-readable-final-output");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.WARN);
        assertThat(finding.nodeIds()).containsExactly("llm_final");
    }

    @Test
    void reportsUnsupportedSuccessfulBusinessClaimWithoutAuthoritativeTool() {
        WorkflowDefinition definition = linear(new WorkflowNode(
                "llm_claim",
                "llm",
                Map.of("prompt", "直接告诉客户退款已成功并且物流查询成功。")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "退款处理")),
                "core-unsupported-business-claims");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.BLOCK);
        assertThat(finding.evidence().toString()).contains("退款已成功", "物流查询成功");
    }

    @Test
    void orderLookupDoesNotAuthorizeARefundSuccessClaim() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("llm_claim", "llm",
                                Map.of("prompt", "根据订单查询结果直接告诉客户退款已成功：{{lastOutput}}")),
                        node("end", "end")),
                List.of(edge("start", "tool_order"), edge("tool_order", "llm_claim"), edge("llm_claim", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "查询订单后处理退款")),
                "core-unsupported-business-claims");

        assertThat(finding.evidence().toString()).contains("退款已成功", "llm_claim");
    }

    @Test
    void crmLookupDoesNotAuthorizeLogisticsQuerySuccessClaim() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_crm", "tool", Map.of(
                                "toolName", "queryCustomerCRM",
                                "arguments", Map.of("customerId", "{{input.customerId}}"))),
                        new WorkflowNode("llm_claim", "llm", Map.of(
                                "prompt", "Tell the customer 物流查询成功 using {{lastOutput}}.")),
                        node("end", "end")),
                List.of(edge("start", "tool_crm"), edge("tool_crm", "llm_claim"), edge("llm_claim", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Query CRM and logistics using their own approved tools.")),
                "core-unsupported-business-claims");

        assertThat(finding.nodeIds()).containsExactly("llm_claim");
        assertThat(finding.evidence().toString()).contains("物流查询成功");
    }

    @Test
    void toolOnOnlyOneBranchDoesNotAuthorizeClaimAfterMerge() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("condition_lookup", "condition", Map.of(
                                "left", "{{input.lookup}}",
                                "operator", "equals",
                                "right", true)),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("llm_claim", "llm", Map.of(
                                "prompt", "Tell the customer 订单查询成功: {{nodes.tool_order.output}}")),
                        node("end", "end")),
                List.of(
                        edge("start", "condition_lookup"),
                        new WorkflowEdge("condition_lookup", "tool_order", "true"),
                        new WorkflowEdge("condition_lookup", "llm_claim", "false"),
                        edge("tool_order", "llm_claim"),
                        edge("llm_claim", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "Order lookup may be skipped.")),
                "core-unsupported-business-claims");

        assertThat(finding.nodeIds()).containsExactly("llm_claim");
    }

    @Test
    void branchPredicateDerivedFromDominatingToolCanAuthorizeOrderQueryClaim() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("condition_found", "condition", Map.of(
                                "left", "{{lastOutput.found}}",
                                "operator", "equals",
                                "right", true)),
                        new WorkflowNode("llm_claim", "llm", Map.of("prompt", "告诉客户订单查询成功。")),
                        new WorkflowNode("llm_missing", "llm", Map.of("prompt", "未找到订单，请核对订单号。")),
                        node("end", "end")),
                List.of(
                        edge("start", "tool_order"),
                        edge("tool_order", "condition_found"),
                        new WorkflowEdge("condition_found", "llm_claim", "true"),
                        new WorkflowEdge("condition_found", "llm_missing", "false"),
                        edge("llm_claim", "end"),
                        edge("llm_missing", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("core", "Only claim an order query succeeded on found=true."));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("core-unsupported-business-claims");
    }

    @Test
    void negativeComparisonOperatorsCannotAuthorizeSuccessfulToolClaim() {
        for (String operator : List.of("notEquals", "notContains")) {
            WorkflowDefinition definition = orderSuccessPredicateWorkflow(operator, true, "true");

            WorkflowGovernanceFinding finding = finding(
                    governanceService.evaluateStatic(definition, context("core", "Claim success only positively.")),
                    "core-unsupported-business-claims");

            assertThat(finding.nodeIds()).as(operator).contains("llm_claim");
        }
    }

    @Test
    void equalsFalsePredicateAuthorizesSuccessOnlyOnExplicitFalseBranch() {
        WorkflowDefinition definition = orderSuccessPredicateWorkflow("equals", false, "false");

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("core", "found=false means the false edge is the successful result branch."));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("core-unsupported-business-claims");
    }

    @Test
    void reportsSingleLabelClassifierWhenLockedSpecRequiresCompoundIssues() {
        WorkflowDefinition definition = classifiedBranch(
                "判断 issueType，只选择 damaged 或 shipping 中的一个。",
                schema(Map.of("issueType", Map.of(
                        "type", "string",
                        "enum", List.of("damaged", "shipping")))),
                "{{nodes.llm_classify.parsed.issueType}}",
                "damaged");

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce",
                        "锁定规格：客户可能同时反馈商品破损和物流延迟，必须保留多个问题并分别处理。")),
                "cs-multi-issue-preservation");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.WARN);
        assertThat(finding.nodeIds()).contains("llm_classify");
        assertThat(finding.evidence().toString()).contains("issueType");
    }

    @Test
    void reportsLowConfidenceClassifierWithoutClarificationPath() {
        Map<String, Object> classifierSchema = schema(Map.of(
                "intent", Map.of("type", "string", "enum", List.of("order_query", "other")),
                "confidence", Map.of("type", "number")));
        WorkflowDefinition definition = classifiedBranch(
                "识别客服订单意图和 confidence。",
                classifierSchema,
                "{{nodes.llm_classify.parsed.intent}}",
                "order_query");

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce",
                        "低置信度时必须澄清，否则按客服意图路由")),
                "cs-low-confidence-clarification");

        assertThat(finding.nodeIds()).contains("llm_classify");
    }

    @Test
    void reportsClassifierWithoutConfidenceOrUnknownSignal() {
        WorkflowDefinition definition = classifiedBranch(
                "Classify the customer support intent.",
                schema(Map.of("intent", Map.of(
                        "type", "string",
                        "enum", List.of("order_query", "refund")))),
                "{{nodes.llm_classify.parsed.intent}}",
                "order_query");

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Clarify uncertain customer intents.")),
                "cs-low-confidence-clarification");

        assertThat(finding.nodeIds()).containsExactly("llm_classify");
        assertThat(finding.evidence().toString()).contains("missingUncertaintySignal");
    }

    @Test
    void randomConfidenceMentionAndClarificationTextDoNotFormLowConfidenceBranch() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("llm_classify", "llm", Map.of(
                                "prompt", "Classify support intent and confidence.",
                                "outputMode", "json",
                                "outputSchema", schema(Map.of(
                                        "intent", Map.of("type", "string"),
                                        "confidence", Map.of("type", "number"))))),
                        new WorkflowNode("llm_reply", "llm", Map.of(
                                "prompt", "Confidence={{nodes.llm_classify.parsed.confidence}}. "
                                        + "Please clarify the request somewhere in the reply.")),
                        node("end", "end")),
                List.of(edge("start", "llm_classify"), edge("llm_classify", "llm_reply"),
                        edge("llm_reply", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Clarify only when confidence is low.")),
                "cs-low-confidence-clarification");

        assertThat(finding.evidence().toString()).contains("lowConfidenceBranch", "false");
    }

    @Test
    void acceptsRealLowConfidenceConditionLeadingToClarification() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("llm_classify", "llm", Map.of(
                                "prompt", "Classify support intent and confidence.",
                                "outputMode", "json",
                                "outputSchema", schema(Map.of(
                                        "intent", Map.of("type", "string"),
                                        "confidence", Map.of("type", "number"))))),
                        new WorkflowNode("condition_confidence", "condition", Map.of(
                                "left", "{{nodes.llm_classify.parsed.confidence}}",
                                "operator", "lessThan",
                                "right", 0.6)),
                        new WorkflowNode("llm_clarify", "llm", Map.of(
                                "prompt", "I am not certain. Please clarify your request.")),
                        new WorkflowNode("llm_route", "llm", Map.of("prompt", "Continue with the classified intent.")),
                        node("end", "end")),
                List.of(
                        edge("start", "llm_classify"),
                        edge("llm_classify", "condition_confidence"),
                        new WorkflowEdge("condition_confidence", "llm_clarify", "true"),
                        new WorkflowEdge("condition_confidence", "llm_route", "false"),
                        edge("llm_clarify", "end"),
                        edge("llm_route", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("customer-service-ecommerce", "Clarify when confidence is below 0.6."));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("cs-low-confidence-clarification");
    }

    @Test
    void acceptsStateBackedUnknownPredicateTracedToDominatingClassifierWriter() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("llm_classify", "llm", Map.of(
                                "prompt", "Classify the support intent, including unknown.",
                                "outputMode", "json",
                                "outputSchema", schema(Map.of(
                                        "intent", Map.of("type", "string",
                                                "enum", List.of("order_query", "unknown")))),
                                "writeState", Map.of("intent", "{{lastOutput.parsed.intent}}"))),
                        new WorkflowNode("condition_unknown", "condition", Map.of(
                                "left", "{{state.intent}}",
                                "operator", "equals",
                                "right", "unknown")),
                        new WorkflowNode("llm_clarify", "llm", Map.of(
                                "prompt", "I am uncertain. Please clarify your request.")),
                        new WorkflowNode("llm_route", "llm", Map.of("prompt", "Handle the known intent.")),
                        node("end", "end")),
                List.of(
                        edge("start", "llm_classify"),
                        edge("llm_classify", "condition_unknown"),
                        new WorkflowEdge("condition_unknown", "llm_clarify", "true"),
                        new WorkflowEdge("condition_unknown", "llm_route", "false"),
                        edge("llm_clarify", "end"),
                        edge("llm_route", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("customer-service-ecommerce", "Clarify when persisted intent is unknown."));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("cs-low-confidence-clarification", "core-output-contract-consistency");
    }

    @Test
    void reportsInventedLogisticsStatusWithoutOrderLookup() {
        WorkflowDefinition definition = linear(new WorkflowNode(
                "llm_status",
                "llm",
                Map.of("prompt", "直接告诉客户：您的包裹正在配送，预计明天到达。")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce",
                        "回答客户订单物流状态")),
                "cs-real-order-and-logistics-lookup");

        assertThat(finding.nodeIds()).containsExactly("llm_status");
    }

    @Test
    void reportsInventedVipStatusWithoutCrmLookup() {
        WorkflowDefinition definition = linear(new WorkflowNode(
                "llm_vip",
                "llm",
                Map.of("prompt", "直接告诉客户：您是 VIP 客户，可以享受专属权益。")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce",
                        "客服回答客户 VIP 权益")),
                "cs-authoritative-crm-vip-data");

        assertThat(finding.nodeIds()).containsExactly("llm_vip");
    }

    @Test
    void reportsMissingRequiredDataClarificationAndToolFailureFallback() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("llm_reply", "llm",
                                Map.of("prompt", "根据订单查询结果直接回答客户：{{lastOutput}}")),
                        node("end", "end")),
                List.of(edge("start", "tool_order"), edge("tool_order", "llm_reply"), edge("llm_reply", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(definition, context(
                "customer-service-ecommerce",
                "锁定规格：查询订单物流；没有订单号必须先补充，工具失败或查无结果必须说明并转人工。"));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .contains("cs-missing-order-clarification", "cs-tool-failure-fallback");
    }

    @Test
    void clarificationOnSiblingBranchDoesNotProtectOrderLookup() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("condition_route", "condition", Map.of(
                                "left", "{{input.mode}}",
                                "operator", "equals",
                                "right", "clarify")),
                        new WorkflowNode("llm_unrelated", "llm", Map.of("prompt", "请提供订单号")),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("llm_reply", "llm", Map.of("prompt", "根据查询结果回复")),
                        node("end", "end")),
                List.of(
                        edge("start", "condition_route"),
                        new WorkflowEdge("condition_route", "llm_unrelated", "true"),
                        new WorkflowEdge("condition_route", "tool_order", "false"),
                        edge("llm_unrelated", "end"),
                        edge("tool_order", "llm_reply"),
                        edge("llm_reply", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce",
                        "订单查询缺少订单号时必须澄清")),
                "cs-missing-order-clarification");

        assertThat(finding.nodeIds()).containsExactly("tool_order");
    }

    @Test
    void successOnlyFoundWordingDoesNotCountAsToolFailureFallback() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("llm_reply", "llm",
                                Map.of("prompt", "当 found=true 时，告诉客户订单 found：{{lastOutput}}")),
                        node("end", "end")),
                List.of(edge("start", "tool_order"), edge("tool_order", "llm_reply"), edge("llm_reply", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce",
                        "订单查询必须处理工具失败和查无结果")),
                "cs-tool-failure-fallback");

        assertThat(finding.nodeIds()).containsExactly("tool_order");
    }

    @Test
    void foundConditionWithoutFailureResponseDoesNotCountAsFallback() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("condition_found", "condition", Map.of(
                                "left", "{{lastOutput.found}}",
                                "operator", "equals",
                                "right", true)),
                        new WorkflowNode("llm_found", "llm", Map.of("prompt", "根据查询结果回答客户")),
                        new WorkflowNode("llm_not_found", "llm", Map.of("prompt", "请稍后再查看")),
                        node("end", "end")),
                List.of(
                        edge("start", "tool_order"),
                        edge("tool_order", "condition_found"),
                        new WorkflowEdge("condition_found", "llm_found", "true"),
                        new WorkflowEdge("condition_found", "llm_not_found", "false"),
                        edge("llm_found", "end"),
                        edge("llm_not_found", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce",
                        "订单查询必须处理查无结果")),
                "cs-tool-failure-fallback");

        assertThat(finding.nodeIds()).containsExactly("tool_order");
    }

    @Test
    void fallbackForLaterCrmToolDoesNotProtectEarlierOrderTool() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("tool_crm", "tool", Map.of(
                                "toolName", "queryCustomerCRM",
                                "arguments", Map.of("customerId", "{{input.customerId}}"))),
                        new WorkflowNode("llm_crm_fallback", "llm", Map.of(
                                "prompt", "If the CRM lookup has no result, transfer CRM handling to a human.")),
                        node("end", "end")),
                List.of(edge("start", "tool_order"), edge("tool_order", "tool_crm"),
                        edge("tool_crm", "llm_crm_fallback"), edge("llm_crm_fallback", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Look up both order and CRM data with independent fallbacks.")),
                "cs-tool-failure-fallback");

        assertThat(finding.nodeIds()).contains("tool_order");
        assertThat(finding.evidence().toString()).contains("tool_order", "queryOrderAPI");
    }

    @Test
    void noResultBranchDoesNotClaimToHandleThrownToolExecutionFailure() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("condition_found", "condition", Map.of(
                                "left", "{{lastOutput.found}}",
                                "operator", "equals",
                                "right", true)),
                        new WorkflowNode("llm_found", "llm", Map.of("prompt", "Reply from the order result.")),
                        new WorkflowNode("llm_not_found", "llm", Map.of(
                                "prompt", "No result was found. Ask the customer to verify the order number.")),
                        node("end", "end")),
                List.of(
                        edge("start", "tool_order"),
                        edge("tool_order", "condition_found"),
                        new WorkflowEdge("condition_found", "llm_found", "true"),
                        new WorkflowEdge("condition_found", "llm_not_found", "false"),
                        edge("llm_found", "end"),
                        edge("llm_not_found", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Handle no order result and tool execution failure.")),
                "cs-tool-failure-fallback");

        assertThat(finding.nodeIds()).containsExactly("tool_order");
        assertThat(finding.evidence().toString())
                .contains("noResultHandled=true", "executionFailureHandled=false");
    }

    @Test
    void anotherCapabilityFailureTextDoesNotCountAsThisToolsNoResultFallback() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("llm_wrong_fallback", "llm", Map.of(
                                "prompt", "Snapshot: {{lastOutput}}. If the CRM lookup has no result, "
                                        + "transfer CRM handling to a human.")),
                        node("end", "end")),
                List.of(edge("start", "tool_order"), edge("tool_order", "llm_wrong_fallback"),
                        edge("llm_wrong_fallback", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Order lookup requires its own no-result fallback.")),
                "cs-tool-failure-fallback");

        assertThat(finding.evidence().toString()).contains("tool_order", "noResultHandled=false");
    }

    @Test
    void invertedOrderIdExistsBranchesDoNotCountAsMissingIdClarification() {
        WorkflowDefinition definition = orderIdClarificationWorkflow("exists", "true", "false");

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Ask only when orderId is missing, otherwise query the order.")),
                "cs-missing-order-clarification");

        assertThat(finding.nodeIds()).containsExactly("tool_order");
    }

    @Test
    void acceptsOrderIdExistsPresentBranchToToolAndMissingBranchToClarification() {
        WorkflowDefinition definition = orderIdClarificationWorkflow("exists", "false", "true");

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("customer-service-ecommerce", "Ask only when orderId is missing, otherwise query the order."));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("cs-missing-order-clarification");
    }

    @Test
    void continuationTextWithoutFailureStatusBranchCannotClaimExecutionFailureIsHandled() {
        WorkflowCompiler bypassCompiler = mock(WorkflowCompiler.class);
        WorkflowGovernanceService service = new WorkflowGovernanceService(
                ruleCatalog, schemaRegistry, toolGatewayService, bypassCompiler);
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"),
                                "continueOnError", true)),
                        new WorkflowNode("llm_fallback", "llm", Map.of(
                                "prompt", "If the order lookup has no result, transfer order handling. {{lastOutput}}")),
                        node("end", "end")),
                List.of(edge("start", "tool_order"), edge("tool_order", "llm_fallback"),
                        edge("llm_fallback", "end")));

        WorkflowGovernanceFinding finding = finding(
                service.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Handle order no-result and execution failure.")),
                "cs-tool-failure-fallback");

        assertThat(finding.evidence().toString())
                .contains("executionFailureHandled=false", "no downstream fallback consumes this tool's failure status");
    }

    @Test
    void registeredContinuationWithSameCapabilityFailureBranchMarksExecutionFailureHandled() {
        WorkflowCompiler bypassCompiler = mock(WorkflowCompiler.class);
        WorkflowGovernanceService service = new WorkflowGovernanceService(
                ruleCatalog, schemaRegistry, toolGatewayService, bypassCompiler);
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"),
                                "continueOnError", true)),
                        new WorkflowNode("condition_failed", "condition", Map.of(
                                "left", "{{nodes.tool_order.succeeded}}",
                                "operator", "equals",
                                "right", false)),
                        new WorkflowNode("llm_order_fallback", "llm", Map.of(
                                "prompt", "The order lookup failed. Transfer order handling to a human.")),
                        new WorkflowNode("llm_order_success", "llm", Map.of(
                                "prompt", "Use the successful order result.")),
                        node("end", "end")),
                List.of(
                        edge("start", "tool_order"),
                        edge("tool_order", "condition_failed"),
                        new WorkflowEdge("condition_failed", "llm_order_fallback", "true"),
                        new WorkflowEdge("condition_failed", "llm_order_success", "false"),
                        edge("llm_order_fallback", "end"),
                        edge("llm_order_success", "end")));

        WorkflowGovernanceFinding finding = finding(
                service.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Handle order no-result and execution failure.")),
                "cs-tool-failure-fallback");

        assertThat(finding.evidence().toString())
                .contains("noResultHandled=false", "executionFailureHandled=true", "runtime-continuation-enabled");
    }

    @Test
    void failureBranchForDifferentCapabilityDoesNotHandleThisToolsExecutionFailure() {
        WorkflowCompiler bypassCompiler = mock(WorkflowCompiler.class);
        WorkflowGovernanceService service = new WorkflowGovernanceService(
                ruleCatalog, schemaRegistry, toolGatewayService, bypassCompiler);
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "continueOnError", true)),
                        new WorkflowNode("condition_failed", "condition", Map.of(
                                "left", "{{nodes.tool_order.succeeded}}",
                                "operator", "equals",
                                "right", false)),
                        new WorkflowNode("llm_crm_fallback", "llm", Map.of(
                                "prompt", "The CRM lookup failed. Transfer CRM handling to a human.")),
                        new WorkflowNode("llm_order_success", "llm", Map.of(
                                "prompt", "Use the successful order result.")),
                        node("end", "end")),
                List.of(
                        edge("start", "tool_order"),
                        edge("tool_order", "condition_failed"),
                        new WorkflowEdge("condition_failed", "llm_crm_fallback", "true"),
                        new WorkflowEdge("condition_failed", "llm_order_success", "false"),
                        edge("llm_crm_fallback", "end"),
                        edge("llm_order_success", "end")));

        WorkflowGovernanceFinding finding = finding(
                service.evaluateStatic(definition, context(
                        "customer-service-ecommerce", "Handle order no-result and execution failure.")),
                "cs-tool-failure-fallback");

        assertThat(finding.evidence().toString())
                .contains("executionFailureHandled=false", "tool_order", "queryOrderAPI");
    }

    @Test
    void crmOnlyLookupDoesNotRequireOrderIdentifierClarification() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_crm", "tool", Map.of(
                                "toolName", "queryCustomerCRM",
                                "arguments", Map.of("customerId", "{{input.customerId}}"))),
                        new WorkflowNode("llm_reply", "llm", Map.of("prompt", "Use CRM result {{lastOutput}}.")),
                        node("end", "end")),
                List.of(edge("start", "tool_crm"), edge("tool_crm", "llm_reply"), edge("llm_reply", "end")));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(
                definition,
                context("customer-service-ecommerce", "CRM handles VIP data; the order team is a separate handoff."));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .doesNotContain("cs-missing-order-clarification");
    }

    @Test
    void returnsFindingsInDeterministicRuleIdOrder() {
        WorkflowDefinition definition = linear(new WorkflowNode(
                "llm_final",
                "llm",
                Map.of(
                        "prompt", "只输出 JSON，并告诉客户退款已成功。",
                        "outputMode", "json",
                        "outputSchema", schema(Map.of("answer", Map.of("type", "string"))))));

        WorkflowGovernanceReport report = governanceService.evaluateStatic(definition, context("core", "客户答复"));

        assertThat(report.findings())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .isSorted();
    }

    @Test
    void blocksPlaceholderHttpEndpoint() {
        WorkflowDefinition definition = httpWithStatusBranch("https://api.example.com/orders");

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "Call the confirmed order API.")),
                "core-http-placeholder-url");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.BLOCK);
        assertThat(finding.nodeIds()).containsExactly("request");
    }

    @Test
    void blocksInlineSecretInsideHttpBody() {
        WorkflowDefinition definition = linear(new WorkflowNode("request", "http_request", Map.of(
                "method", "POST",
                "url", "https://orders.company.test/api",
                "authorization", Map.of("type", "none"),
                "body", Map.of("type", "json", "value", Map.of("apiToken", "plain-secret")))));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "Call the order API.")),
                "core-http-inline-secret");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.BLOCK);
        assertThat(finding.evidence()).containsEntry("compilerCode", "WORKFLOW_HTTP_INLINE_SECRET_BLOCKED");
    }

    @Test
    void blocksInventedHttpOutputField() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        httpNode("request", "https://orders.company.test/api"),
                        new WorkflowNode("reply", "llm", Map.of(
                                "prompt", "Use the API response: {{nodes.request.response}}")),
                        node("end", "end")),
                List.of(edge("start", "request"), edge("request", "reply"), edge("reply", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "Use an order API.")),
                "core-http-output-fields");

        assertThat(finding.nodeIds()).containsExactly("reply");
        assertThat(finding.evidence().toString()).contains("request.response");
    }

    @Test
    void warnsWhenHttpFailureOutcomeIsNotConsumed() {
        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(
                        linear(httpNode("request", "https://orders.company.test/api")),
                        context("core", "Call an order API.")),
                "core-http-failure-handling");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.WARN);
        assertThat(finding.nodeIds()).containsExactly("request");
    }

    @Test
    void blocksVariableAggregatorCandidateThatIsNotUpstream() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("aggregate", "variable_aggregator", Map.of(
                                "mode", "single",
                                "outputType", "string",
                                "variables", List.of("{{nodes.later.answer}}"))),
                        new WorkflowNode("later", "llm", Map.of("prompt", "Answer")),
                        node("end", "end")),
                List.of(edge("start", "aggregate"), edge("aggregate", "later"), edge("later", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "Aggregate branch output.")),
                "core-variable-aggregator-candidates");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.BLOCK);
    }

    @Test
    void blocksDangerousReportFileName() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("writer", "llm", Map.of("prompt", "Write a safe report")),
                        new WorkflowNode("export", "report_export", Map.of(
                                "content", "{{nodes.writer.answer}}",
                                "formats", List.of("pdf"),
                                "fileName", "../quarterly-report.exe")),
                        node("end", "end")),
                List.of(edge("start", "writer"), edge("writer", "export"), edge("export", "end")));

        WorkflowGovernanceFinding finding = finding(
                governanceService.evaluateStatic(definition, context("core", "Export the report as PDF.")),
                "core-report-file-name");

        assertThat(finding.severity()).isEqualTo(WorkflowGovernanceFinding.Severity.BLOCK);
        assertThat(finding.nodeIds()).containsExactly("export");
    }

    private WorkflowBuilderContext context(String domain, String lockedSpec) {
        return new WorkflowBuilderContext(
                domain,
                lockedSpec,
                "",
                ruleCatalog.activePacks(domain),
                schemaRegistry.listSchemas(),
                executableTools(),
                List.of(),
                "test context");
    }

    private List<ToolDescriptor> executableTools() {
        return List.of(
                new ToolDescriptor("getCurrentTime", "Current time", "local", false),
                new ToolDescriptor("calculate", "Calculator", "local", false),
                new ToolDescriptor("queryOrderAPI", "Order lookup", "local", false),
                new ToolDescriptor("queryCustomerCRM", "CRM lookup", "mcp", true, "crm", "{}"));
    }

    private WorkflowDefinition linear(WorkflowNode middle) {
        return new WorkflowDefinition(
                List.of(node("start", "start"), middle, node("end", "end")),
                List.of(edge("start", middle.id()), edge(middle.id(), "end")));
    }

    private WorkflowNode httpNode(String id, String url) {
        return new WorkflowNode(id, "http_request", Map.of(
                "method", "GET",
                "url", url,
                "authorization", Map.of("type", "none"),
                "body", Map.of("type", "none")));
    }

    private WorkflowDefinition httpWithStatusBranch(String url) {
        return new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        httpNode("request", url),
                        new WorkflowNode("status", "condition", Map.of(
                                "left", "{{nodes.request.statusCode}}",
                                "operator", "equals",
                                "right", 200)),
                        new WorkflowNode("success", "llm", Map.of("prompt", "Request succeeded")),
                        new WorkflowNode("failure", "llm", Map.of("prompt", "Request failed")),
                        node("end", "end")),
                List.of(
                        edge("start", "request"),
                        edge("request", "status"),
                        new WorkflowEdge("status", "success", "true"),
                        new WorkflowEdge("status", "failure", "false"),
                        edge("success", "end"),
                        edge("failure", "end")));
    }

    private WorkflowDefinition classifiedBranch(String prompt, Map<String, Object> outputSchema,
            String left, String right) {
        return new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("llm_classify", "llm", Map.of(
                                "prompt", prompt,
                                "outputMode", "json",
                                "outputSchema", outputSchema)),
                        new WorkflowNode("condition_route", "condition", Map.of(
                                "left", left,
                                "operator", "equals",
                                "right", right)),
                        new WorkflowNode("llm_true", "llm", Map.of("prompt", "生成自然语言处理结果")),
                        new WorkflowNode("llm_false", "llm", Map.of("prompt", "生成自然语言兜底结果")),
                        node("end", "end")),
                List.of(
                        edge("start", "llm_classify"),
                        edge("llm_classify", "condition_route"),
                        new WorkflowEdge("condition_route", "llm_true", "true"),
                        new WorkflowEdge("condition_route", "llm_false", "false"),
                        edge("llm_true", "end"),
                        edge("llm_false", "end")));
    }

    private WorkflowDefinition orderSuccessPredicateWorkflow(String operator, Object right,
            String claimEdgeCondition) {
        String otherEdgeCondition = "true".equals(claimEdgeCondition) ? "false" : "true";
        return new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("user_query", "{{input.message}}"))),
                        new WorkflowNode("condition_found", "condition", Map.of(
                                "left", "{{lastOutput.found}}",
                                "operator", operator,
                                "right", right)),
                        new WorkflowNode("llm_claim", "llm", Map.of("prompt", "告诉客户订单查询成功。")),
                        new WorkflowNode("llm_other", "llm", Map.of("prompt", "请核对订单信息。")),
                        node("end", "end")),
                List.of(
                        edge("start", "tool_order"),
                        edge("tool_order", "condition_found"),
                        new WorkflowEdge("condition_found", "llm_claim", claimEdgeCondition),
                        new WorkflowEdge("condition_found", "llm_other", otherEdgeCondition),
                        edge("llm_claim", "end"),
                        edge("llm_other", "end")));
    }

    private WorkflowDefinition orderIdClarificationWorkflow(String operator, String askEdgeCondition,
            String toolEdgeCondition) {
        return new WorkflowDefinition(
                List.of(
                        node("start", "start"),
                        new WorkflowNode("condition_order_id", "condition", Map.of(
                                "left", "{{input.orderId}}",
                                "operator", operator)),
                        new WorkflowNode("llm_ask", "llm", Map.of("prompt", "请提供订单号。")),
                        new WorkflowNode("tool_order", "tool", Map.of(
                                "toolName", "queryOrderAPI",
                                "arguments", Map.of("orderId", "{{input.orderId}}"))),
                        new WorkflowNode("llm_reply", "llm", Map.of("prompt", "Use order result {{lastOutput}}.")),
                        node("end", "end")),
                List.of(
                        edge("start", "condition_order_id"),
                        new WorkflowEdge("condition_order_id", "llm_ask", askEdgeCondition),
                        new WorkflowEdge("condition_order_id", "tool_order", toolEdgeCondition),
                        edge("llm_ask", "end"),
                        edge("tool_order", "llm_reply"),
                        edge("llm_reply", "end")));
    }

    private Map<String, Object> schema(Map<String, Object> properties) {
        return Map.of("type", "object", "properties", properties);
    }

    private WorkflowNode node(String id, String type) {
        return new WorkflowNode(id, type, Map.of());
    }

    private WorkflowEdge edge(String from, String to) {
        return new WorkflowEdge(from, to);
    }

    private WorkflowGovernanceFinding finding(WorkflowGovernanceReport report, String ruleId) {
        return report.findings().stream()
                .filter(candidate -> candidate.ruleId().equals(ruleId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing finding " + ruleId + ": " + report.findings()));
    }
}
