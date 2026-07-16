package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.chat.TokenUsage;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.support.TestAlibabaPolicies;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolExecutionPolicy;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.tool.ToolProvider;
import com.example.agentdemo.workflow.report.ReportArtifactMetadata;
import com.example.agentdemo.workflow.report.ReportExportCommand;
import com.example.agentdemo.workflow.report.ReportExportResult;
import com.example.agentdemo.workflow.report.ReportPrintPreviewMetadata;
import com.example.agentdemo.workflow.report.WorkflowReportExportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowNodeExecutorTest {

    private final WorkflowVariableResolver variableResolver = new WorkflowVariableResolver();

    @Test
    void reportExportNodeRendersSelectedInputAndReturnsArtifactMetadata() {
        WorkflowReportExportService reportService = mock(WorkflowReportExportService.class);
        Instant expiresAt = Instant.parse("2026-08-14T00:00:00Z");
        ReportArtifactMetadata pdf = new ReportArtifactMetadata("art-1", "pdf", "报告.pdf",
                "application/pdf", 100, "a".repeat(64), expiresAt,
                "/api/workflow-artifacts/art-1/content");
        ReportPrintPreviewMetadata preview = new ReportPrintPreviewMetadata("art-2",
                "/api/workflow-artifacts/art-2/content");
        when(reportService.export(any())).thenReturn(
                new ReportExportResult("exp-1", List.of(pdf), pdf, preview, expiresAt));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class), null, reportService);
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "# 报告内容"), "owner-a");

        Object output = executor.execute("run-1", new WorkflowNode("report-1", "report_export", Map.of(
                "content", "{{input.message}}",
                "formats", List.of("pdf"),
                "fileName", "研究报告")), state);

        assertThat(output).isInstanceOfSatisfying(Map.class,
                map -> {
                    assertThat(map).containsEntry("exportId", "exp-1");
                    assertThat(map.get("printPreview")).isInstanceOfSatisfying(ReportPrintPreviewMetadata.class,
                            value -> assertThat(value.contentUrl())
                                    .isEqualTo("/api/workflow-artifacts/art-2/content"));
                });
        ArgumentCaptor<ReportExportCommand> command = ArgumentCaptor.forClass(ReportExportCommand.class);
        verify(reportService).export(command.capture());
        assertThat(command.getValue().renderRequest().markdown()).isEqualTo("# 报告内容");
        assertThat(command.getValue().ownerId()).isEqualTo("owner-a");
        assertThat(command.getValue().fileName()).isEqualTo("研究报告");
    }

    @Test
    void retrieverNodeRendersQueryTemplateBeforeSearching() {
        RagService ragService = mock(RagService.class);
        RetrievedContext context = new RetrievedContext(1L, "Doc", "content", 0.9);
        when(ragService.retrieve("hello", 3, "run-1")).thenReturn(List.of(context));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(ragService, mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("retriever_1", "retriever", Map.of("query", "{{input.message}}", "topK", 3)),
                state);

        verify(ragService).retrieve("hello", 3, "run-1");
        assertThat(output).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsEntry("query", "hello");
            assertThat(map).containsEntry("retrievedContext", List.of(context));
        });
    }

    @Test
    void customAiNodeAppliesInstructionToNamedRenderedInputsAndParsesStructuredOutput() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), anyString()))
                .thenReturn(AiModelResult.ok("{\"company\":\"Example AI\",\"patentCount\":12}", null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of(
                "company", "Example AI",
                "patentEvidence", List.of("US-1", "US-2")));

        Object output = executor.execute("run-1", new WorkflowNode("patent_metrics", "custom", Map.of(
                "mode", "ai",
                "inputs", Map.of(
                        "company", "{{input.company}}",
                        "evidence", "{{input.patentEvidence}}"),
                "instruction", "根据证据提取公司专利指标，不得补造缺失数据。",
                "outputMode", "json",
                "outputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "company", Map.of("type", "string"),
                                "patentCount", Map.of("type", "integer")),
                        "required", List.of("company", "patentCount")))), state);

        assertThat(output).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsEntry("mode", "ai");
            assertThat(map.get("inputs")).isEqualTo(Map.of(
                    "company", "Example AI",
                    "evidence", List.of("US-1", "US-2")));
            assertThat(map.get("parsed")).isEqualTo(Map.of("company", "Example AI", "patentCount", 12));
        });
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).generate(anyString(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("根据证据提取公司专利指标，不得补造缺失数据。")
                .contains("\"company\":\"Example AI\"")
                .contains("\"evidence\":[\"US-1\",\"US-2\"]");
    }

    @Test
    void customTemplateNodeRendersDeterministicOutputWithoutCallingModel() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of(
                "company", "Example AI",
                "patentCount", 12));

        Object output = executor.execute("run-1", new WorkflowNode("patent_summary", "custom", Map.of(
                "mode", "template",
                "inputs", Map.of("company", "{{input.company}}"),
                "template", Map.of(
                        "summary", "{{input.company}} 共有 {{input.patentCount}} 项已验证专利",
                        "count", "{{input.patentCount}}"))), state);

        assertThat(output).isEqualTo(Map.of(
                "mode", "template",
                "inputs", Map.of("company", "Example AI"),
                "output", Map.of(
                        "summary", "Example AI 共有 12 项已验证专利",
                        "count", 12)));
        verifyNoInteractions(aiModelService);
    }

    @Test
    void toolNodeUsesToolGatewaySoRemoteToolsCanBeCalled() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new RemoteEchoProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_echo"));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                gateway, variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(), mock(com.example.agentdemo.workflow.WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("mcp_1", "tool", Map.of(
                        "toolName", "remote_echo",
                        "arguments", Map.of("text", "{{input}}")
                )),
                state);

        assertThat(output).isInstanceOf(ToolExecutionLog.class);
        assertThat(state.lastOutput()).isEqualTo("remote:hello");
        assertThat(state.toolCalls()).hasSize(1);
    }

    @Test
    void tavilySearchNodeRendersItsQueryAndExposesSearchResultsDirectly() {
        ToolGatewayService gateway = mock(ToolGatewayService.class);
        Map<String, Object> searchOutput = Map.of(
                "query", "Spring AI 1.1 release notes",
                "answer", "Spring AI 1.1 is available.",
                "results", List.of(Map.of("title", "Release notes", "url", "https://example.test/release")),
                "requestId", "req-123");
        Instant now = Instant.now();
        when(gateway.execute(eq("tavilySearch"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(new ToolExecutionLog("tavilySearch", Map.of(), searchOutput, true, null, now, now));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                gateway, variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "Spring AI 1.1 release notes"));
        WorkflowNode node = new WorkflowNode("search_web", "tavily_search", Map.of(
                "query", "{{input.message}}",
                "searchDepth", "advanced",
                "topic", "news",
                "maxResults", 6,
                "includeAnswer", true,
                "includeRawContent", false,
                "timeRange", "week",
                "includeDomains", List.of("spring.io"),
                "excludeDomains", List.of("spam.test")));

        Object output = executor.execute("run-1", node, state);

        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(gateway).execute(eq("tavilySearch"), arguments.capture());
        assertThat(arguments.getValue())
                .containsEntry("query", "Spring AI 1.1 release notes")
                .containsEntry("search_depth", "advanced")
                .containsEntry("topic", "news")
                .containsEntry("max_results", 6)
                .containsEntry("include_answer", true)
                .containsEntry("include_raw_content", false)
                .containsEntry("time_range", "week")
                .containsEntry("include_domains", List.of("spring.io"))
                .containsEntry("exclude_domains", List.of("spam.test"));
        assertThat(output).isEqualTo(searchOutput);
        assertThat(state.lastOutput()).isEqualTo(searchOutput);
        assertThat(state.toolCalls()).hasSize(1);
    }

    @Test
    void largeTavilyResultsRemainAddressableByDownstreamLlmPrompt() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), anyString())).thenReturn(AiModelResult.ok("grounded report"));
        ToolGatewayService gateway = new ToolGatewayService(List.of(new LargeTavilyOutputProvider()));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                gateway, variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "AI hardware"));
        WorkflowNode search = new WorkflowNode("tavily_search_1", "tavily_search", Map.of(
                "query", "{{input.message}}",
                "includeAnswer", true));

        executor.execute("run-1", search, state);
        state.recordNodeOutput(search.id());
        executor.execute("run-1", new WorkflowNode("llm_report", "llm", Map.of(
                "prompt", "摘要：{{nodes.tavily_search_1.answer}}\n"
                        + "结果：{{nodes.tavily_search_1.results}}")), state);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).generate(anyString(), prompt.capture());
        assertThat(prompt.getValue())
                .contains("Grounded Tavily answer")
                .contains("Primary source")
                .contains("https://example.test/source");
    }

    @Test
    void variableAggregatorSelectsFirstPresentValueAndPreservesEmptyValues() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));
        state.setLastOutput(Map.of("output", ""));
        state.recordNodeOutput("branch_b");

        Object output = executor.execute("run-1", new WorkflowNode("aggregate", "variable_aggregator", Map.of(
                "mode", "single",
                "outputType", "string",
                "variables", List.of("{{nodes.branch_a.output}}", "{{nodes.branch_b.output}}"))), state);

        assertThat(output).isEqualTo(Map.of("output", ""));
        assertThat(state.lastOutput()).isEqualTo(output);
    }

    @Test
    void groupedVariableAggregatorReportsMissingGroupAndExposesNestedOutputs() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));
        state.setLastOutput(Map.of("result", Map.of("department", "shipping")));
        state.recordNodeOutput("shipping");

        Object output = executor.execute("run-1", new WorkflowNode("aggregate", "variable_aggregator", Map.of(
                "mode", "groups",
                "groups", List.of(Map.of(
                        "key", "departmentResult",
                        "outputType", "object",
                        "variables", List.of("{{nodes.after_sales.result}}", "{{nodes.shipping.result}}"))))), state);

        assertThat(output).isEqualTo(Map.of(
                "departmentResult", Map.of("output", Map.of("department", "shipping"))));

        WorkflowExecutionState missingState = new WorkflowExecutionState(Map.of("message", "hello"));
        assertThatThrownBy(() -> executor.execute("run-1",
                new WorkflowNode("aggregate", "variable_aggregator", Map.of(
                        "mode", "groups",
                        "groups", List.of(Map.of(
                                "key", "departmentResult",
                                "outputType", "object",
                                "variables", List.of("{{nodes.after_sales.result}}"))))), missingState))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("WORKFLOW_VARIABLE_UNAVAILABLE");
                    assertThat(exception.getMessage()).contains("departmentResult");
                });
    }

    @Test
    void toolNodeRejectsMissingToolNameWithoutCallingGateway() {
        ToolGatewayService gateway = mock(ToolGatewayService.class);
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                gateway, variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute(
                "run-1",
                new WorkflowNode("tool_missing", "tool", Map.of()),
                state))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("WORKFLOW_VALIDATION_FAILED"))
                .hasMessageContaining("tool_missing.toolName is required");
        verifyNoInteractions(gateway);
    }

    @Test
    void toolNodeRejectsBlankToolNameWithoutCallingGateway() {
        ToolGatewayService gateway = mock(ToolGatewayService.class);
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                gateway, variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute(
                "run-1",
                new WorkflowNode("tool_blank", "tool", Map.of("toolName", "   ")),
                state))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("WORKFLOW_VALIDATION_FAILED"))
                .hasMessageContaining("tool_blank.toolName is required");
        verifyNoInteractions(gateway);
    }

    @Test
    void throwsWhenLlmReturnsFallback() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(AiModelResult.fallback("mock fallback payload", "model unavailable"));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(com.example.agentdemo.workflow.WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1", new WorkflowNode("llm_1", "llm", Map.of()), state))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ALIBABA_LLM_UNAVAILABLE");
    }

    @Test
    void llmNodeUsesConfiguredModelAndReturnsTokenUsageDetails() {
        AiModelService aiModelService = mock(AiModelService.class);
        TokenUsage usage = new TokenUsage("dashscope", "qwen-max", 14, 9, 23,
                Map.of("input_tokens", 14, "output_tokens", 9));
        when(aiModelService.generateWithModel(anyString(), eq("Summarize: hello"), eq("qwen-max")))
                .thenReturn(AiModelResult.ok("summary", usage));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("llm_1", "llm", Map.of(
                        "prompt", "Summarize: {{input.message}}",
                        "model", "qwen-max"
                )),
                state);

        assertThat(output).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsEntry("answer", "summary");
            assertThat(map).containsEntry("model", "qwen-max");
            assertThat(map).containsEntry("tokenUsage", usage);
        });
        verify(aiModelService).generateWithModel(anyString(), eq("Summarize: hello"), eq("qwen-max"));
    }

    @Test
    void llmNodeParsesJsonObjectAnswerIntoParsedOutput() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("""
                        {"intent":"order_query","confidence":0.93,"orderIds":["20260630001"]}
                        """, null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of("prompt", "Classify: {{input.message}}")),
                state);

        assertThat(output).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsKey("parsed");
            Map<?, ?> parsed = (Map<?, ?>) map.get("parsed");
            assertThat(parsed.get("intent")).isEqualTo("order_query");
            assertThat(parsed.get("confidence")).isEqualTo(0.93);
            assertThat(parsed.get("orderIds")).isEqualTo(List.of("20260630001"));
        });
    }

    @Test
    void llmNodeRequiresJsonWhenOutputModeIsJson() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("I think this is an order question.", null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json"
                )), state)
        )
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_LLM_OUTPUT_INVALID"))
                .hasMessageContaining("must be valid JSON");
    }

    @Test
    void llmNodeRejectsJsonOutputMissingRequiredSchemaField() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("{\"confidence\":0.91}", null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", intentSchema()
                )), state))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_LLM_OUTPUT_INVALID"))
                .hasMessageContaining("Missing required workflow node output field: intent");
    }

    @Test
    void llmNodeRejectsJsonOutputEnumOutsideSchemaContract() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("{\"intent\":\"do_anything\",\"confidence\":0.91}", null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", intentSchema()
                )), state))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_LLM_OUTPUT_INVALID"))
                .hasMessageContaining("Workflow node output field intent must be one of");
    }

    @Test
    void llmNodeRejectsAdditionalJsonFieldsAndDoesNotWriteInvalidState() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("""
                        {"intent":"order_query","confidence":0.91,"freeformAction":"call_any_tool"}
                        """, null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Throwable thrown = catchThrowable(() -> executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", intentSchema(),
                        "writeState", Map.of("intent", "{{lastOutput.parsed.intent}}")
                )), state));

        assertThat(thrown)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_LLM_OUTPUT_INVALID"))
                .hasMessageContaining("Unsupported workflow node output field: freeformAction");
        assertThat(state.stateVariables()).doesNotContainKey("intent");
        assertThat(state.answer()).isNull();
    }

    @Test
    void llmNodeAcceptsJsonSchemaAndAllowsWriteState() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("""
                        {"intent":"order_query","confidence":0.91}
                        """, null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", intentSchema(),
                        "writeState", Map.of(
                                "intent", "{{lastOutput.parsed.intent}}",
                                "confidence", "{{lastOutput.parsed.confidence}}"
                        )
                )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class, map -> {
            assertThat(map).containsKey("parsed");
            Map<?, ?> parsed = (Map<?, ?>) map.get("parsed");
            assertThat(parsed.get("intent")).isEqualTo("order_query");
            assertThat(parsed.get("confidence")).isEqualTo(0.91);
        });
        assertThat(state.stateVariables())
                .containsEntry("intent", "order_query")
                .containsEntry("confidence", 0.91);
        assertThat(state.answer()).contains("order_query");
    }

    @Test
    void llmNodeAddsOutputSchemaContractToSystemPrompt() {
        AiModelService aiModelService = mock(AiModelService.class);
        when(aiModelService.generate(anyString(), eq("Classify: hello")))
                .thenReturn(AiModelResult.ok("""
                        {"intent":"order_query","confidence":0.91}
                        """, null));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), aiModelService,
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.strictMode(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        executor.execute("run-1",
                new WorkflowNode("llm_intent", "llm", Map.of(
                        "prompt", "Classify: {{input.message}}",
                        "outputMode", "json",
                        "outputSchema", intentSchema()
                )), state);

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(aiModelService).generate(systemPrompt.capture(), eq("Classify: hello"));
        assertThat(systemPrompt.getValue())
                .contains("Structured output contract")
                .contains("Return exactly one valid JSON object")
                .contains("Do not omit required fields")
                .contains("\"required\"")
                .contains("intent", "confidence", "additionalProperties");
    }

    @Test
    void nodeWritesExplicitWorkflowStateAfterSuccessfulExecution() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of(
                "message", "hello",
                "order", Map.of("status", "SHIPPED")));

        executor.execute("run-1", new WorkflowNode("start", "start", Map.of(
                "writeState", Map.of(
                        "intent", "order_query",
                        "message", "{{lastOutput.message}}",
                        "order", "{{input.order}}"
                )
        )), state);

        assertThat(state.stateVariables())
                .containsEntry("intent", "order_query")
                .containsEntry("message", "hello")
                .containsEntry("order", Map.of("status", "SHIPPED"));
    }

    @Test
    void failedToolNodeThrowsBusinessExceptionWithToolErrorCategory() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new FailingRemoteProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_fail"));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                gateway, variableResolver, com.example.agentdemo.support.TestAlibabaPolicies.legacyFallbackAllowed(), mock(com.example.agentdemo.workflow.WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThatThrownBy(() -> executor.execute("run-1",
                new WorkflowNode("mcp_1", "tool", Map.of("toolName", "remote_fail")),
                state))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ToolExecutionLog.ERROR_REMOTE_TOOL));
        assertThat(state.toolCalls()).singleElement().satisfies(log -> {
            assertThat(log.succeeded()).isFalse();
            assertThat(log.errorMessage()).isEqualTo("remote failed");
        });
    }

    @Test
    void continuedToolFailureExposesStructuredStatusAndPreservesToolLogForTrace() {
        ToolGatewayService gateway = new ToolGatewayService(List.of(new FailingRemoteProvider()),
                ToolExecutionPolicy.allowOnlyRemoteTools("remote_fail"));
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                gateway, variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));
        WorkflowNode node = new WorkflowNode("mcp_1", "tool", Map.of(
                "toolName", "remote_fail",
                "continueOnError", true));

        AtomicReference<WorkflowNodeExecutionResult> resultRef = new AtomicReference<>();
        Throwable failure = catchThrowable(() -> resultRef.set(
                new WorkflowNodeRunner(executor, mock(ExecutorService.class)).execute("run-1", node, state)));

        assertThat(failure).as("continueOnError=true must not abort the tool node").isNull();
        WorkflowNodeExecutionResult result = resultRef.get();
        state.recordNodeOutput(node.id());

        ToolExecutionLog failedLog = state.toolCalls().getFirst();
        assertThat(result.output()).isInstanceOfSatisfying(Map.class, output -> {
            assertThat(output)
                    .containsEntry("status", "FAILED")
                    .containsEntry("succeeded", false)
                    .containsEntry("toolName", "remote_fail")
                    .containsEntry("errorMessage", "remote failed")
                    .containsEntry("errorCategory", ToolExecutionLog.ERROR_REMOTE_TOOL)
                    .containsEntry("toolExecutionLog", failedLog);
        });
        assertThat(result.traceOutput()).isEqualTo(result.output());
        assertThat(variableResolver.renderString(
                "{{lastOutput.status}}|{{lastOutput.errorMessage}}|{{nodes.mcp_1.errorCategory}}", state))
                .isEqualTo("FAILED|remote failed|REMOTE_TOOL_ERROR");
        assertThat(state.toolCalls()).containsExactly(failedLog);
    }

    @Test
    void evaluationFixtureForcesToolFailureWithoutCallingRealGateway() {
        ToolGatewayService gateway = mock(ToolGatewayService.class);
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(
                mock(RagService.class), mock(AiModelService.class), gateway, variableResolver,
                TestAlibabaPolicies.legacyFallbackAllowed(), mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(
                WorkflowEvaluationFixtures.withToolFailure(Map.of("message", "test"), "queryOrderAPI"));
        WorkflowNode node = new WorkflowNode("order_lookup", "tool", Map.of(
                "toolName", "queryOrderAPI",
                "continueOnError", true,
                "arguments", Map.of("userQuery", "{{input.message}}")));

        Object output = executor.execute("run-1", node, state);

        assertThat(output).isInstanceOfSatisfying(Map.class, failure -> assertThat(failure)
                .containsEntry("status", "FAILED")
                .containsEntry("succeeded", false)
                .containsEntry("errorCategory", WorkflowEvaluationFixtures.ERROR_EVALUATION_TOOL_FAILURE));
        assertThat(state.toolCalls()).singleElement().satisfies(log -> {
            assertThat(log.succeeded()).isFalse();
            assertThat(log.errorCategory()).isEqualTo(WorkflowEvaluationFixtures.ERROR_EVALUATION_TOOL_FAILURE);
        });
        verifyNoInteractions(gateway);
    }

    @Test
    void evaluateConditionSupportsNumericComparisons() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));

        assertThat(executor.evaluateCondition("5", "greaterthan", "3", false)).isTrue();
        assertThat(executor.evaluateCondition("2", "lessthan", "4", false)).isTrue();
        assertThat(executor.evaluateCondition("5", "lessthan", "3", false)).isFalse();
    }

    @Test
    void evaluateConditionRejectsNonNumericValuesForNumericOperators() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));

        assertThatThrownBy(() -> executor.evaluateCondition("abc", "greaterthan", "3", false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Numeric comparison requires numeric left/right values");
    }

    @Test
    void conditionNodeSupportsAllCompositeConditions() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));
        state.setStateVariable("intent", "order_query");
        state.setStateVariable("order", Map.of("status", "SHIPPED"));

        Object output = executor.execute("run-1", new WorkflowNode("check", "condition", Map.of(
                "mode", "all",
                "conditions", List.of(
                        Map.of("left", "{{state.intent}}", "operator", "equals", "right", "order_query"),
                        Map.of("left", "{{state.order.status}}", "operator", "equals", "right", "SHIPPED")
                )
        )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map).containsEntry("result", true));
        assertThat(state.lastConditionResult()).isTrue();
    }

    @Test
    void conditionNodeAllCompositeConditionsRequiresEveryCondition() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));
        state.setStateVariable("intent", "order_query");
        state.setStateVariable("order", Map.of("status", "PENDING_RETURN"));

        Object output = executor.execute("run-1", new WorkflowNode("check", "condition", Map.of(
                "mode", "all",
                "conditions", List.of(
                        Map.of("left", "{{state.intent}}", "operator", "equals", "right", "order_query"),
                        Map.of("left", "{{state.order.status}}", "operator", "equals", "right", "SHIPPED")
                )
        )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map).containsEntry("result", false));
        assertThat(state.lastConditionResult()).isFalse();
    }

    @Test
    void conditionNodeSupportsAnyCompositeConditions() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));
        state.setStateVariable("intent", "order_query");
        state.setStateVariable("order", Map.of("status", "SHIPPED"));

        Object output = executor.execute("run-1", new WorkflowNode("check", "condition", Map.of(
                "mode", "any",
                "conditions", List.of(
                        Map.of("left", "{{state.intent}}", "operator", "equals", "right", "product_consult"),
                        Map.of("left", "{{state.order.status}}", "operator", "equals", "right", "SHIPPED")
                )
        )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map).containsEntry("result", true));
        assertThat(state.lastConditionResult()).isTrue();
    }

    @Test
    void conditionNodeIgnoresEmptyCompositeConditionsDefaultAndUsesLegacyConfig() {
        WorkflowNodeExecutor executor = new WorkflowNodeExecutor(mock(RagService.class), mock(AiModelService.class),
                new ToolGatewayService(List.of()), variableResolver, TestAlibabaPolicies.legacyFallbackAllowed(),
                mock(WorkflowInlineExecutionService.class));
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        Object output = executor.execute("run-1", new WorkflowNode("check", "condition", Map.of(
                "left", "{{input.message}}",
                "operator", "equals",
                "right", "hello",
                "conditions", List.of()
        )), state);

        assertThat(output).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map).containsEntry("result", true));
        assertThat(state.lastConditionResult()).isTrue();
    }

    private Map<String, Object> intentSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("intent", "confidence"),
                "properties", Map.of(
                        "intent", Map.of("type", "string",
                                "enum", List.of("order_query", "refund_policy", "unknown")),
                        "confidence", Map.of("type", "number"),
                        "orderId", Map.of("type", "string")),
                "additionalProperties", false);
    }

    private static final class RemoteEchoProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "remote_echo".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            return new ToolExecutionLog(toolName, arguments, "remote:" + arguments.get("text"), true, null, now, now);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("remote_echo", "Remote echo", providerName(), true));
        }

    }

    private static final class FailingRemoteProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "test-mcp";
        }

        @Override
        public boolean supports(String toolName) {
            return "remote_fail".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            return ToolExecutionLog.failure(toolName, arguments, "remote failed", now, now,
                    new ToolDescriptor(toolName, "Remote failure", providerName(), true),
                    ToolExecutionLog.ERROR_REMOTE_TOOL);
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("remote_fail", "Remote failure", providerName(), true));
        }

    }

    private static final class LargeTavilyOutputProvider implements ToolProvider {

        @Override
        public String providerName() {
            return "tavily";
        }

        @Override
        public boolean supports(String toolName) {
            return "tavilySearch".equals(toolName);
        }

        @Override
        public ToolExecutionLog execute(String toolName, Map<String, Object> arguments) {
            Instant now = Instant.now();
            Map<String, Object> output = Map.of(
                    "query", arguments.get("query"),
                    "answer", "Grounded Tavily answer",
                    "results", List.of(Map.of(
                            "title", "Primary source",
                            "url", "https://example.test/source",
                            "content", "evidence ".repeat(3_000))));
            return ToolExecutionLog.success(toolName, arguments, output, now, now, tools().getFirst());
        }

        @Override
        public List<ToolDescriptor> tools() {
            return List.of(new ToolDescriptor("tavilySearch", "Large Tavily output", providerName(), false));
        }

    }

}
