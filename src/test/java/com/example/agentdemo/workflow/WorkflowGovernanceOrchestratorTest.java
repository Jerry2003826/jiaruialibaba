package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.workflow.governance.WorkflowBuilderContext;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationAssertionType;
import com.example.agentdemo.workflow.governance.WorkflowBuilderContextService;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationAssertionResult;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCase;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseKind;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseResult;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseStatus;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationErrorOrigin;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationReport;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationService;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceFinding;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceReport;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceService;
import com.example.agentdemo.workflow.governance.WorkflowRuleCatalog;
import com.example.agentdemo.workflow.governance.WorkflowRulePack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowGovernanceOrchestratorTest {

    private final WorkflowStructuredOutputAutoconfigurer autoconfigurer =
            new WorkflowStructuredOutputAutoconfigurer();
    private final WorkflowCompiler compiler = mock(WorkflowCompiler.class);
    private final WorkflowBuilderContextService contextService = mock(WorkflowBuilderContextService.class);
    private final WorkflowGovernanceService governanceService = mock(WorkflowGovernanceService.class);
    private final WorkflowEvaluationService evaluationService = mock(WorkflowEvaluationService.class);
    private final WorkflowRuleCatalog ruleCatalog = mock(WorkflowRuleCatalog.class);
    private final WorkflowGovernanceOrchestrator orchestrator = new WorkflowGovernanceOrchestrator(
            autoconfigurer,
            compiler,
            new WorkflowDefinitionContractValidator(),
            contextService,
            governanceService,
            evaluationService,
            ruleCatalog,
            new ObjectMapper());

    @Test
    void evaluatesStaticAndRuntimeCasesAndReturnsActivePackVersions() {
        WorkflowDefinition definition = validDefinition();
        WorkflowExecutionPlan plan = mock(WorkflowExecutionPlan.class);
        WorkflowEvaluationCase coreCase = runtimeCase("core-case");
        WorkflowEvaluationCase domainCase = runtimeCase("domain-case");
        WorkflowRulePack corePack = pack("core", "1.2.0", coreCase);
        WorkflowRulePack domainPack = pack("customer-service-ecommerce", "2.1.0", domainCase);
        WorkflowBuilderContext context = context("customer-service-ecommerce", corePack);
        Map<String, Object> supplementalInput = Map.of("message", "please check order 12345678");
        WorkflowEvaluationCaseResult passed = new WorkflowEvaluationCaseResult(
                "core-case", supplementalInput, WorkflowEvaluationCaseStatus.PASSED,
                List.of("run-1"), List.of("start", "end"), List.<WorkflowEvaluationAssertionResult>of(),
                "ok", "ok", null, null);

        when(contextService.build(eq(null), any(String.class), eq(null))).thenReturn(context);
        when(compiler.compile(definition)).thenReturn(plan);
        when(governanceService.evaluateStatic(definition, context))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(ruleCatalog.detectDomain(definition)).thenReturn("customer-service-ecommerce");
        when(ruleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(corePack, domainPack));
        when(evaluationService.evaluate(eq(plan), argThat(cases -> cases.stream()
                .map(WorkflowEvaluationCase::id).toList().equals(List.of("core-case", "domain-case"))),
                eq(supplementalInput)))
                .thenReturn(new WorkflowEvaluationReport(supplementalInput, List.of(passed)));

        WorkflowGovernanceEvaluationResponse response = orchestrator.evaluate(
                definition,
                Map.of("domain", "customer-service-ecommerce", "goal", "route customer requests"),
                supplementalInput);

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.READY);
        assertThat(response.testResults()).containsExactly(passed);
        assertThat(response.activeRulePacks())
                .extracting(WorkflowActiveRulePack::id, WorkflowActiveRulePack::version)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("core", "1.2.0"),
                        org.assertj.core.groups.Tuple.tuple("customer-service-ecommerce", "2.1.0"));
    }

    @Test
    void skipsRuntimeEvaluationWhenStaticGovernanceBlocks() {
        WorkflowDefinition definition = validDefinition();
        WorkflowExecutionPlan plan = mock(WorkflowExecutionPlan.class);
        WorkflowRulePack corePack = pack("core", "1.2.0");
        WorkflowBuilderContext context = context("core", corePack);
        WorkflowGovernanceFinding blocker = new WorkflowGovernanceFinding(
                "core-test", WorkflowGovernanceFinding.Severity.BLOCK,
                WorkflowGovernanceFinding.Phase.STATIC, "blocked", List.of("end"), "repair", Map.of());
        when(contextService.build(eq(null), any(String.class), eq(null))).thenReturn(context);
        when(compiler.compile(definition)).thenReturn(plan);
        when(governanceService.evaluateStatic(definition, context))
                .thenReturn(new WorkflowGovernanceReport(List.of(blocker)));
        when(ruleCatalog.detectDomain(definition)).thenReturn(null);

        WorkflowGovernanceEvaluationResponse response = orchestrator.evaluate(definition, "core workflow", Map.of());

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.BLOCKED);
        assertThat(response.governanceReport().blockers()).containsExactly(blocker);
        assertThat(response.testResults()).isEmpty();
        verify(evaluationService, never()).evaluate(any(), any(), any());
    }

    @Test
    void mapsRuntimeInfrastructureFailureToInfraError() {
        WorkflowDefinition definition = validDefinition();
        WorkflowExecutionPlan plan = mock(WorkflowExecutionPlan.class);
        WorkflowEvaluationCase runtimeCase = runtimeCase("core-case");
        WorkflowRulePack corePack = pack("core", "1.2.0", runtimeCase);
        WorkflowBuilderContext context = context("core", corePack);
        WorkflowEvaluationCaseResult failed = new WorkflowEvaluationCaseResult(
                "core-case", Map.of("message", "hello"), WorkflowEvaluationCaseStatus.INFRA_ERROR,
                List.of(), List.of(), List.of(), null, "provider unavailable",
                WorkflowEvaluationErrorOrigin.PROVIDER, "ALIBABA_LLM_UNAVAILABLE");
        when(contextService.build(eq(null), any(String.class), eq(null))).thenReturn(context);
        when(compiler.compile(definition)).thenReturn(plan);
        when(governanceService.evaluateStatic(definition, context))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(ruleCatalog.detectDomain(definition)).thenReturn(null);
        when(evaluationService.evaluate(plan, List.of(runtimeCase), Map.of("message", "hello")))
                .thenReturn(new WorkflowEvaluationReport(Map.of("message", "hello"), List.of(failed)));

        WorkflowGovernanceEvaluationResponse response = orchestrator.evaluate(
                definition, "core workflow", Map.of("message", "hello"));

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.INFRA_ERROR);
        assertThat(response.testResults()).containsExactly(failed);
    }

    @Test
    void invalidTemplateContractBecomesStructuredBlock() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm", "llm", Map.of("prompt", "{{state.missing}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "llm"), new WorkflowEdge("llm", "end")));
        WorkflowRulePack corePack = pack("core", "1.2.0");
        when(contextService.build(eq(null), any(String.class), eq(null))).thenReturn(context("core", corePack));
        when(ruleCatalog.detectDomain(definition)).thenReturn(null);

        WorkflowGovernanceEvaluationResponse response = orchestrator.evaluate(definition, "core workflow", Map.of());

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.BLOCKED);
        assertThat(response.governanceReport().blockers())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .containsExactly("core-workflow-validity");
        assertThat(response.governanceReport().blockers().getFirst().message()).contains("state.missing");
        verify(compiler, never()).compile(any());
        verify(evaluationService, never()).evaluate(any(), any(), any());
    }

    @Test
    void keepsEightMandatoryCasesThenAddsAtMostFourSafeLockedSpecCases() {
        WorkflowDefinition definition = validDefinition();
        WorkflowExecutionPlan plan = mock(WorkflowExecutionPlan.class);
        WorkflowEvaluationCase[] mandatoryCases = java.util.stream.IntStream.rangeClosed(1, 8)
                .mapToObj(index -> runtimeCase("mandatory-" + index))
                .toArray(WorkflowEvaluationCase[]::new);
        WorkflowRulePack domainPack = pack("customer-service-ecommerce", "2.1.0", mandatoryCases);
        Map<String, Object> lockedSpec = Map.of(
                "domain", "customer-service-ecommerce",
                "testCases", List.of(
                        "第一条用户描述",
                        Map.of("prompt", "第二条描述"),
                        Map.of("input", "第三条输入"),
                        Map.of(
                                "prompt", "第四条对象输入",
                                "input", Map.of("message", "第四条消息", "locale", "zh-CN"),
                                "assertions", List.of(Map.of("type", "TOOL_SUCCEEDED")),
                                "fixture", Map.of("failTool", "queryOrderAPI")),
                        "第五条必须被边界丢弃"));

        when(contextService.build(eq(null), any(String.class), eq(null))).thenAnswer(invocation ->
                context("customer-service-ecommerce", (String) invocation.getArgument(1), domainPack));
        when(compiler.compile(definition)).thenReturn(plan);
        when(governanceService.evaluateStatic(eq(definition), any(WorkflowBuilderContext.class)))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(ruleCatalog.activePacks("customer-service-ecommerce")).thenReturn(List.of(domainPack));
        when(ruleCatalog.detectDomain(definition)).thenReturn(null);
        when(evaluationService.evaluate(eq(plan), any(), eq(Map.of()))).thenAnswer(invocation -> {
            List<WorkflowEvaluationCase> cases = invocation.getArgument(1);
            List<WorkflowEvaluationCaseResult> results = cases.stream()
                    .map(workflowCase -> new WorkflowEvaluationCaseResult(
                            workflowCase.id(), workflowCase.runtimeInput(), WorkflowEvaluationCaseStatus.PASSED,
                            List.of(), List.of(), List.of(), "ok", null, null))
                    .toList();
            return new WorkflowEvaluationReport(Map.of(), results);
        });

        WorkflowGovernanceEvaluationResponse response = orchestrator.evaluate(definition, lockedSpec, Map.of());

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.READY);
        assertThat(response.testResults()).extracting(WorkflowEvaluationCaseResult::caseId)
                .containsExactly(
                        "mandatory-1", "mandatory-2", "mandatory-3", "mandatory-4",
                        "mandatory-5", "mandatory-6", "mandatory-7", "mandatory-8",
                        "locked-spec-1", "locked-spec-2", "locked-spec-3", "locked-spec-4");
        verify(evaluationService).evaluate(eq(plan), argThat(cases -> {
            List<WorkflowEvaluationCase> lockedCases = cases.subList(8, 12);
            return lockedCases.stream().allMatch(workflowCase ->
                    workflowCase.kind() == WorkflowEvaluationCaseKind.RUNTIME
                            && workflowCase.fixture() == null
                            && workflowCase.assertions().size() == 1
                            && workflowCase.assertions().getFirst().type()
                            == WorkflowEvaluationAssertionType.FINAL_OUTPUT_CUSTOMER_READABLE)
                    && lockedCases.get(3).runtimeInput().equals(
                    Map.of("message", "第四条消息", "locale", "zh-CN"));
        }), eq(Map.of()));
    }

    @Test
    void blocksCoreWorkflowWhenLockedSpecRuntimeCaseFails() {
        WorkflowDefinition definition = validDefinition();
        WorkflowExecutionPlan plan = mock(WorkflowExecutionPlan.class);
        WorkflowRulePack corePack = pack("core", "1.2.0");
        Map<String, Object> lockedSpec = Map.of("testCases", List.of("请回答客户的问题"));
        WorkflowEvaluationCaseResult failed = new WorkflowEvaluationCaseResult(
                "locked-spec-1", Map.of("message", "请回答客户的问题"),
                WorkflowEvaluationCaseStatus.DESIGN_FAILED, List.of("run-1"), List.of("start", "end"),
                List.of(), "{}", WorkflowEvaluationErrorOrigin.DESIGN, "EVALUATION_ASSERTION_FAILED");

        when(contextService.build(eq(null), any(String.class), eq(null))).thenAnswer(invocation ->
                context("core", (String) invocation.getArgument(1), corePack));
        when(compiler.compile(definition)).thenReturn(plan);
        when(governanceService.evaluateStatic(eq(definition), any(WorkflowBuilderContext.class)))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(ruleCatalog.activePacks("core")).thenReturn(List.of(corePack));
        when(ruleCatalog.detectDomain(definition)).thenReturn(null);
        when(evaluationService.evaluate(eq(plan), argThat(cases -> cases.size() == 1
                        && cases.getFirst().id().equals("locked-spec-1")), eq(Map.of())))
                .thenReturn(new WorkflowEvaluationReport(Map.of(), List.of(failed)));

        WorkflowGovernanceEvaluationResponse response = orchestrator.evaluate(definition, lockedSpec, Map.of());

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.BLOCKED);
        assertThat(response.testResults()).containsExactly(failed);
    }

    @Test
    void mapsRejectedEvaluationCapacityToInfrastructureError() {
        WorkflowDefinition definition = validDefinition();
        WorkflowExecutionPlan plan = mock(WorkflowExecutionPlan.class);
        WorkflowRulePack corePack = pack("core", "1.2.0", runtimeCase("core-case"));
        WorkflowBuilderContext context = context("core", corePack);
        when(compiler.compile(definition)).thenReturn(plan);
        when(governanceService.evaluateStatic(definition, context))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(ruleCatalog.detectDomain(definition)).thenReturn(null);
        when(ruleCatalog.activePacks("core")).thenReturn(List.of(corePack));
        when(evaluationService.evaluate(eq(plan), any(), eq(Map.of())))
                .thenThrow(new RejectedExecutionException("evaluation queue full"));

        WorkflowGovernanceEvaluationResponse response = orchestrator.evaluate(
                definition, context, Map.of(), List.of());

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.INFRA_ERROR);
        assertThat(response.testResults()).isEmpty();
    }

    @Test
    void blocksReadyWhenNoRuntimeCaseWasExecuted() {
        WorkflowDefinition definition = validDefinition();
        WorkflowExecutionPlan plan = mock(WorkflowExecutionPlan.class);
        WorkflowRulePack corePack = pack("core", "1.2.0");
        WorkflowBuilderContext context = context("core", corePack);
        when(compiler.compile(definition)).thenReturn(plan);
        when(governanceService.evaluateStatic(definition, context))
                .thenReturn(new WorkflowGovernanceReport(List.of()));
        when(ruleCatalog.detectDomain(definition)).thenReturn(null);
        when(ruleCatalog.activePacks("core")).thenReturn(List.of(corePack));
        when(evaluationService.evaluate(eq(plan), any(), eq(Map.of())))
                .thenReturn(new WorkflowEvaluationReport(Map.of(), List.of()));

        WorkflowGovernanceEvaluationResponse response = orchestrator.evaluate(
                definition, context, Map.of(), List.of());

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.BLOCKED);
        assertThat(response.testResults()).isEmpty();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<WorkflowEvaluationCase>> casesCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(evaluationService).evaluate(eq(plan), casesCaptor.capture(), eq(Map.of()));
        assertThat(casesCaptor.getValue())
                .extracting(WorkflowEvaluationCase::id)
                .containsExactly("core-runtime-smoke");
        assertThat(response.governanceReport().blockers())
                .extracting(WorkflowGovernanceFinding::ruleId)
                .containsExactly("core-runtime-evaluation-required");
    }

    @Test
    void preservesRequestNullThenRejectsItAtServiceBoundaryWithStableValidationError() {
        Map<String, Object> supplementalInput = new LinkedHashMap<>();
        supplementalInput.put("message", null);
        WorkflowGovernanceEvaluationRequest request = new WorkflowGovernanceEvaluationRequest(
                validDefinition(), null, supplementalInput);

        assertThat(request.supplementalInput()).containsEntry("message", null);
        assertThatThrownBy(() -> orchestrator.evaluate(
                request.workflowDefinition(), request.lockedSpec(), request.supplementalInput()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("VALIDATION_ERROR"));
    }

    private WorkflowDefinition validDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
    }

    private WorkflowBuilderContext context(String domain, WorkflowRulePack... packs) {
        return new WorkflowBuilderContext(domain, "locked", "", List.of(packs), List.of(), List.of(), List.of(), "");
    }

    private WorkflowBuilderContext context(String domain, String lockedSpec, WorkflowRulePack... packs) {
        return new WorkflowBuilderContext(
                domain, lockedSpec, "", List.of(packs), List.of(), List.of(), List.of(), "");
    }

    private WorkflowRulePack pack(String id, String version, WorkflowEvaluationCase... cases) {
        return new WorkflowRulePack(id, version, List.of(id), List.of(), List.of(), List.of(cases));
    }

    private WorkflowEvaluationCase runtimeCase(String id) {
        return new WorkflowEvaluationCase(
                id, "prompt", "expected", WorkflowEvaluationCaseKind.RUNTIME,
                Map.of("message", id), List.of());
    }
}
