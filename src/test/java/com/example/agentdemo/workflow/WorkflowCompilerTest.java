package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowCompilerTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());

    @Test
    void compilesCustomAiNodeWithNamedReachableInputs() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("search", "tavily_search", Map.of("query", "{{input.message}}")),
                new WorkflowNode("patent_metrics", "custom", Map.of(
                        "mode", "ai",
                        "inputs", Map.of("evidence", "{{nodes.search.results}}"),
                        "instruction", "提取每家公司的专利指标，不得补造数据。",
                        "outputMode", "json",
                        "outputSchema", Map.of(
                                "type", "object",
                                "properties", Map.of("companies", Map.of("type", "array"))))),
                new WorkflowNode("end", "end", Map.of()));

        assertThat(compiler.compile(definition).nodesById()).containsKey("patent_metrics");
    }

    @Test
    void rejectsCustomAiNodeWithoutAnInstruction() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("custom", "custom", Map.of("mode", "ai", "inputs", Map.of())),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("instruction is required in ai mode");
    }

    @Test
    void rejectsCustomNodeInputThatReferencesANonUpstreamNode() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("custom", "custom", Map.of(
                        "mode", "template",
                        "inputs", Map.of("future", "{{nodes.writer.answer}}"),
                        "template", "{{nodes.writer.answer}}")),
                new WorkflowNode("writer", "llm", Map.of("prompt", "Write {{input.message}}")),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must reference an upstream node: writer");
    }

    @Test
    void exposesCustomTextOutputToReportExport() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("custom", "custom", Map.of(
                        "mode", "template",
                        "inputs", Map.of("topic", "{{input.message}}"),
                        "template", "# {{input.message}}")),
                new WorkflowNode("report", "report_export", Map.of(
                        "content", "{{nodes.custom.output}}",
                        "formats", List.of("markdown"))),
                new WorkflowNode("end", "end", Map.of()));

        assertThat(compiler.compile(definition).nodesById()).containsKey("report");
    }

    @Test
    void compilesAReportExportNodeWithSupportedFormats() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("writer", "llm", Map.of("prompt", "Write {{input.message}}")),
                new WorkflowNode("report", "report_export", Map.of(
                        "content", "{{nodes.writer.answer}}",
                        "formats", List.of("pdf", "docx"))),
                new WorkflowNode("end", "end", Map.of()));

        assertThat(compiler.compile(definition).nodesById()).containsKey("report");
    }

    @Test
    void rejectsReportExportWithoutAFormat() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("writer", "llm", Map.of("prompt", "Write {{input.message}}")),
                new WorkflowNode("report", "report_export", Map.of(
                        "content", "{{nodes.writer.answer}}",
                        "formats", List.of())),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one format");
    }

    @Test
    void rejectsReportContentThatIsNotAReachableUpstreamStringVariable() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("writer", "llm", Map.of("prompt", "Write {{input.message}}")),
                new WorkflowNode("report", "report_export", Map.of(
                        "content", "{{input.message}}",
                        "formats", List.of("pdf"))),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("content must be an exact reachable upstream string variable");
    }

    @Test
    void compilesValidLinearWorkflow() {
        WorkflowExecutionPlan plan = compiler.compile(definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("retriever", "retriever", Map.of("topK", 3)),
                new WorkflowNode("llm", "llm", Map.of("prompt", "Answer: {{context}}")),
                new WorkflowNode("end", "end", Map.of())));

        assertThat(plan.linear()).isTrue();
        assertThat(plan.linearNodes())
                .extracting(WorkflowNode::id)
                .containsExactly("start", "retriever", "llm", "end");
    }

    @Test
    void compilesConditionBranchWorkflow() {
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("check_intent", "condition", Map.of(
                                "left", "{{input.intent}}",
                                "operator", "equals",
                                "right", "time")),
                        new WorkflowNode("tool_time", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("llm_fallback", "llm", Map.of("prompt", "Answer {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "check_intent"),
                        new WorkflowEdge("check_intent", "tool_time", "true"),
                        new WorkflowEdge("check_intent", "llm_fallback", "false"),
                        new WorkflowEdge("tool_time", "end"),
                        new WorkflowEdge("llm_fallback", "end"))));

        assertThat(plan.linear()).isFalse();
        assertThat(plan.outgoing("check_intent"))
                .extracting(WorkflowExecutionEdge::condition)
                .containsExactlyInAnyOrder("true", "false");
    }

    @Test
    void compilesConditionBranchWorkflowWithCompositeConditionConfig() {
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("check_intent", "condition", Map.of(
                                "mode", "all",
                                "conditions", List.of(
                                        Map.of("left", "{{state.intent}}", "operator", "equals",
                                                "right", "order_query"),
                                        Map.of("left", "{{state.order.status}}", "operator", "equals",
                                                "right", "SHIPPED")))),
                        new WorkflowNode("tool_order", "tool", Map.of("toolName", "queryOrderAPI")),
                        new WorkflowNode("llm_fallback", "llm", Map.of("prompt", "Answer {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "check_intent"),
                        new WorkflowEdge("check_intent", "tool_order", "true"),
                        new WorkflowEdge("check_intent", "llm_fallback", "false"),
                        new WorkflowEdge("tool_order", "end"),
                        new WorkflowEdge("llm_fallback", "end"))));

        assertThat(plan.linear()).isFalse();
        assertThat(plan.outgoing("check_intent"))
                .extracting(WorkflowExecutionEdge::condition)
                .containsExactlyInAnyOrder("true", "false");
    }

    @Test
    void compilesLlmNodeWithOutputContractConfig() {
        WorkflowExecutionPlan plan = compiler.compile(definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("classify", "llm", Map.of(
                        "prompt", "Classify {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", Map.of(
                                "type", "object",
                                "required", List.of("intent"),
                                "properties", Map.of(
                                        "intent", Map.of("type", "string",
                                                "enum", List.of("order_query", "refund_policy", "unknown"))),
                                "additionalProperties", false))),
                new WorkflowNode("end", "end", Map.of())));

        assertThat(plan.linearNodes())
                .extracting(WorkflowNode::id)
                .containsExactly("start", "classify", "end");
    }

    @Test
    void compilesLlmNodeWithAutomaticOutputContractMarker() {
        WorkflowExecutionPlan plan = compiler.compile(definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("classify", "llm", Map.of(
                        "prompt", "Classify {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", Map.of(
                                "type", "object",
                                "required", List.of("intent"),
                                "properties", Map.of("intent", Map.of("type", "string"))),
                        "autoStructuredOutputContract", "customer_service_intent")),
                new WorkflowNode("end", "end", Map.of())));

        assertThat(plan.linearNodes())
                .extracting(WorkflowNode::id)
                .containsExactly("start", "classify", "end");
    }

    @Test
    void compilesHttpRequestAndVariableAggregatorNodes() {
        WorkflowExecutionPlan plan = compiler.compile(definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("http_orders", "http_request", Map.of(
                        "method", "POST",
                        "url", "https://api.example.test/orders",
                        "headers", List.of(Map.of("key", "Accept", "value", "application/json", "enabled", true)),
                        "params", List.of(),
                        "authorization", Map.of("type", "none"),
                        "body", Map.of("type", "json", "value", Map.of("message", "{{input.message}}")))),
                new WorkflowNode("aggregate", "variable_aggregator", Map.of(
                        "mode", "single",
                        "outputType", "object",
                        "variables", List.of("{{nodes.http_orders.json}}"))),
                new WorkflowNode("end", "end", Map.of())));

        assertThat(plan.linearNodes())
                .extracting(WorkflowNode::type)
                .containsExactly("start", "http_request", "variable_aggregator", "end");
    }

    @Test
    void rejectsInlineSecretsNestedInsideHttpJsonBody() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("http_orders", "http_request", Map.of(
                        "method", "POST",
                        "url", "https://api.example.test/orders",
                        "authorization", Map.of("type", "none"),
                        "body", Map.of("type", "json", "value", Map.of(
                                "message", "{{input.message}}",
                                "apiToken", "plain-text-secret")))),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode())
                                .isEqualTo("WORKFLOW_HTTP_INLINE_SECRET_BLOCKED"));
    }

    @Test
    void rejectsVariableAggregatorReferencesToNonUpstreamNodes() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("aggregate", "variable_aggregator", Map.of(
                        "mode", "single",
                        "outputType", "string",
                        "variables", List.of("{{nodes.later.answer}}"))),
                new WorkflowNode("later", "llm", Map.of("prompt", "Answer {{input.message}}")),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must reference an upstream node");
    }

    @Test
    void rejectsBranchingFromNonConditionNode() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("a", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("b", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "a"),
                        new WorkflowEdge("start", "b"),
                        new WorkflowEdge("a", "end"),
                        new WorkflowEdge("b", "end")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only condition or parallel nodes can branch: start");
    }

    @Test
    void rejectsConditionNodeWithoutTrueAndFalseEdges() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("check", "condition", Map.of("left", "{{input.intent}}")),
                        new WorkflowNode("a", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("b", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "check"),
                        new WorkflowEdge("check", "a", "true"),
                        new WorkflowEdge("check", "b", "true"),
                        new WorkflowEdge("a", "end"),
                        new WorkflowEdge("b", "end")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Condition node outgoing edges must use condition=true and condition=false");
    }

    @Test
    void rejectsUnsupportedConditionOperator() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("check", "condition", Map.of("operator", "matchesRegex")),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config check.operator must be one of");
    }

    @Test
    void rejectsUnknownConfigKeys() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of("unexpected", true)),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported config key for node start: unexpected");
    }

    @Test
    void rejectsMissingRequiredConfigKeys() {
        WorkflowCompiler compiler = new WorkflowCompiler(new RequiredToolNameSchemaRegistry());
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of()),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config tool.toolName is required");
    }

    @Test
    void rejectsRetrieverTopKOutsideSchemaRange() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("retriever", "retriever", Map.of("topK", 21)),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config retriever.topK must be <= 20");
    }

    @Test
    void rejectsRetrieverTopKWithWrongType() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("retriever", "retriever", Map.of("topK", "three")),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config retriever.topK must be integer");
    }

    @Test
    void rejectsBlankToolNameWhenConfigured() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of("toolName", " ")),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config tool.toolName must not be blank");
    }

    @Test
    void rejectsToolArgumentsWithWrongType() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of("arguments", "not-an-object")),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config tool.arguments must be object");
    }

    @Test
    void acceptsWorkflowNodeRetryAndTimeoutControls() {
        WorkflowExecutionPlan plan = compiler.compile(definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of(
                        "toolName", "getCurrentTime",
                        "retryCount", 2,
                        "timeoutMs", 5000)),
                new WorkflowNode("end", "end", Map.of())));

        assertThat(plan.node("tool").config())
                .containsEntry("retryCount", 2)
                .containsEntry("timeoutMs", 5000);
    }

    @Test
    void compilesParallelJoinWorkflow() {
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode("tool_a", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("tool_b", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("join_1", "join", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "parallel_1"),
                        new WorkflowEdge("parallel_1", "tool_a"),
                        new WorkflowEdge("parallel_1", "tool_b"),
                        new WorkflowEdge("tool_a", "join_1"),
                        new WorkflowEdge("tool_b", "join_1"),
                        new WorkflowEdge("join_1", "end"))));

        assertThat(plan.linear()).isFalse();
        assertThat(plan.hasParallelJoin()).isTrue();
        assertThat(plan.incomingNodeIds("join_1")).containsExactlyInAnyOrder("tool_a", "tool_b");
    }

    @Test
    void rejectsParallelBranchesThatDoNotConvergeToValidJoin() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode("tool_a", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("tool_b", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("join_a", "join", Map.of()),
                        new WorkflowNode("join_b", "join", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "parallel_1"),
                        new WorkflowEdge("parallel_1", "tool_a"),
                        new WorkflowEdge("parallel_1", "tool_b"),
                        new WorkflowEdge("tool_a", "join_a"),
                        new WorkflowEdge("tool_b", "join_b"),
                        new WorkflowEdge("join_a", "end"),
                        new WorkflowEdge("join_b", "end")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Join node must have at least two incoming edges: join_a");
    }

    @Test
    void rejectsJoinNodeWithoutParallelParent() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("check", "condition", Map.of("left", "{{input.intent}}")),
                        new WorkflowNode("a", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("b", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("join_1", "join", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "check"),
                        new WorkflowEdge("check", "a", "true"),
                        new WorkflowEdge("check", "b", "false"),
                        new WorkflowEdge("a", "join_1"),
                        new WorkflowEdge("b", "join_1"),
                        new WorkflowEdge("join_1", "end")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Join node must be reached by a parallel node: join_1");
    }

    @Test
    void rejectsWorkflowNodeRetryAndTimeoutOutsideSchemaRange() {
        WorkflowDefinition tooManyRetries = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of("toolName", "getCurrentTime", "retryCount", 6)),
                new WorkflowNode("end", "end", Map.of()));
        WorkflowDefinition tooLongTimeout = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of("toolName", "getCurrentTime", "timeoutMs", 300001)),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(tooManyRetries))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config tool.retryCount must be <= 5");
        assertThatThrownBy(() -> compiler.compile(tooLongTimeout))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config tool.timeoutMs must be <= 300000");
    }

    @Test
    void rejectsWorkflowExceedingNodeBudget() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxNodes(2);
        WorkflowCompiler limitedCompiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry(), properties);

        assertThatThrownBy(() -> limitedCompiler.compile(definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of("toolName", "getCurrentTime")),
                new WorkflowNode("end", "end", Map.of()))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Workflow node count exceeds limit");
    }

    @Test
    void rejectsWorkflowExceedingEdgeBudget() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxEdges(1);
        WorkflowCompiler limitedCompiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry(), properties);

        assertThatThrownBy(() -> limitedCompiler.compile(definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of("toolName", "getCurrentTime")),
                new WorkflowNode("end", "end", Map.of()))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Workflow edge count exceeds limit");
    }

    @Test
    void rejectsParallelNodeExceedingBranchBudget() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setMaxParallelBranches(1);
        WorkflowCompiler limitedCompiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry(), properties);
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode("a", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("b", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("join_1", "join", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "parallel_1"),
                        new WorkflowEdge("parallel_1", "a"),
                        new WorkflowEdge("parallel_1", "b"),
                        new WorkflowEdge("a", "join_1"),
                        new WorkflowEdge("b", "join_1"),
                        new WorkflowEdge("join_1", "end")));

        assertThatThrownBy(() -> limitedCompiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Parallel branch count exceeds limit");
    }

    @Test
    void compilesLoopWithLoopBackAsOnlyAllowedCycle() {
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("loop_1", "loop", Map.of(
                                "maxIterations", 5,
                                "left", "{{input.count}}",
                                "operator", "greaterthan",
                                "right", "0")),
                        new WorkflowNode("body", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("after", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "loop_1"),
                        new WorkflowEdge("loop_1", "body", "body"),
                        new WorkflowEdge("loop_1", "after", "exit"),
                        new WorkflowEdge("body", "loop_back"),
                        new WorkflowEdge("loop_back", "loop_1"),
                        new WorkflowEdge("after", "end"))));

        assertThat(plan.loopBlocks()).hasSize(1);
        assertThat(plan.loopBlocks().getFirst().exitNodeId()).isEqualTo("after");
    }

    @Test
    void rejectsLoopWithoutBodyAndExitEdges() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("loop_1", "loop", Map.of("maxIterations", 3)),
                        new WorkflowNode("body", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("after", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "loop_1"),
                        new WorkflowEdge("loop_1", "body"),
                        new WorkflowEdge("body", "loop_back"),
                        new WorkflowEdge("loop_back", "loop_1"),
                        new WorkflowEdge("after", "end")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Loop node must have body and exit outgoing edges");
    }

    @Test
    void rejectsLoopBackNotPointingToLoopNode() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("loop_1", "loop", Map.of("maxIterations", 3)),
                        new WorkflowNode("body", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("wrong_target", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("after", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "loop_1"),
                        new WorkflowEdge("loop_1", "body", "body"),
                        new WorkflowEdge("loop_1", "after", "exit"),
                        new WorkflowEdge("body", "loop_back"),
                        new WorkflowEdge("loop_back", "wrong_target"),
                        new WorkflowEdge("after", "end")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("loop_back node must point to a loop node");
    }

    @Test
    void rejectsArbitraryWorkflowCycle() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("a", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("b", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "a"),
                        new WorkflowEdge("a", "b"),
                        new WorkflowEdge("b", "a"),
                        new WorkflowEdge("b", "end")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only condition or parallel nodes can branch");
    }

    @Test
    void rejectsSubgraphInsideLoopBody() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("loop_1", "loop", Map.of("maxIterations", 3)),
                        new WorkflowNode("sub_1", "subgraph", Map.of("definitionId", "child")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("after", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "loop_1"),
                        new WorkflowEdge("loop_1", "sub_1", "body"),
                        new WorkflowEdge("loop_1", "after", "exit"),
                        new WorkflowEdge("sub_1", "loop_back"),
                        new WorkflowEdge("loop_back", "loop_1"),
                        new WorkflowEdge("after", "end")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Loop body cannot contain branching or composite nodes");
    }

    @Test
    void rejectsLoopInsideParallelBranch() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("parallel_1", "parallel", Map.of()),
                        new WorkflowNode("loop_1", "loop", Map.of("maxIterations", 3)),
                        new WorkflowNode("body", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("after", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("join_1", "join", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "parallel_1"),
                        new WorkflowEdge("parallel_1", "loop_1"),
                        new WorkflowEdge("loop_1", "body", "body"),
                        new WorkflowEdge("loop_1", "after", "exit"),
                        new WorkflowEdge("body", "loop_back"),
                        new WorkflowEdge("loop_back", "loop_1"),
                        new WorkflowEdge("after", "join_1"),
                        new WorkflowEdge("parallel_1", "join_1"),
                        new WorkflowEdge("join_1", "end")));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Parallel branches only support linear nodes");
    }

    @Test
    void rejectsLoopMaxIterationsOutsideRange() {
        WorkflowDefinition tooLow = loopDefinitionWithMaxIterations(0);
        WorkflowDefinition tooHigh = loopDefinitionWithMaxIterations(51);

        assertThatThrownBy(() -> compiler.compile(tooLow))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config loop_1.maxIterations must be >= 1");
        assertThatThrownBy(() -> compiler.compile(tooHigh))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config loop_1.maxIterations must be <= 50");
    }

    @Test
    void rejectsSubgraphWithoutDefinitionId() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("sub_1", "subgraph", Map.of()),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config sub_1.definitionId is required");
    }

    private WorkflowDefinition loopDefinitionWithMaxIterations(int maxIterations) {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("loop_1", "loop", Map.of("maxIterations", maxIterations)),
                        new WorkflowNode("body", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("after", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "loop_1"),
                        new WorkflowEdge("loop_1", "body", "body"),
                        new WorkflowEdge("loop_1", "after", "exit"),
                        new WorkflowEdge("body", "loop_back"),
                        new WorkflowEdge("loop_back", "loop_1"),
                        new WorkflowEdge("after", "end")));
    }

    private WorkflowDefinition definition(WorkflowNode... nodes) {
        return new WorkflowDefinition(List.of(nodes), edgesFor(nodes));
    }

    private List<WorkflowEdge> edgesFor(WorkflowNode[] nodes) {
        java.util.ArrayList<WorkflowEdge> edges = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.length - 1; i++) {
            edges.add(new WorkflowEdge(nodes[i].id(), nodes[i + 1].id()));
        }
        return edges;
    }

    private static final class RequiredToolNameSchemaRegistry extends WorkflowNodeSchemaRegistry {

        @Override
        public Optional<WorkflowNodeSchema> findSchema(String type) {
            if (!"tool".equals(type)) {
                return super.findSchema(type);
            }
            return Optional.of(new WorkflowNodeSchema(
                    "tool",
                    "Tool",
                    "Test tool schema with required toolName.",
                    List.of(new WorkflowNodeConfigField("toolName", "string", true, null, "Tool name.", Map.of())),
                    List.of(),
                    "A tool execution log."));
        }

    }

}
