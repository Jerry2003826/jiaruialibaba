package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.WorkflowRuntimeProperties;
import com.example.agentdemo.security.SecurityIdentity;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.TraceRun;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunStepResponse;
import com.example.agentdemo.workflow.WorkflowCanceledException;
import com.example.agentdemo.workflow.WorkflowExecutionPlan;
import com.example.agentdemo.workflow.WorkflowEvaluationFixtures;
import com.example.agentdemo.workflow.WorkflowNode;
import com.example.agentdemo.workflow.WorkflowRunBudgetRegistry;
import com.example.agentdemo.workflow.WorkflowRuntime;
import com.example.agentdemo.workflow.WorkflowStepSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowEvaluationServiceTest {

    private final List<WorkflowEvaluationService> services = new ArrayList<>();

    @AfterEach
    void tearDown() {
        services.forEach(WorkflowEvaluationService::close);
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void schedulesEightMandatoryRuntimeCasesInImmutablePackOrderWithoutSupplementalNinthCase() {
        WorkflowRuleCatalog catalog = new WorkflowRuleCatalog();
        List<WorkflowEvaluationCase> activeCases = catalog.activePacks("customer-service-ecommerce").stream()
                .flatMap(pack -> pack.testCases().stream())
                .toList();
        AtomicInteger executions = new AtomicInteger();
        WorkflowRuntime runtime = (runId, plan, input) -> {
            executions.incrementAndGet();
            return passingResult("ok");
        };

        Map<String, Object> supplementalInput = Map.of(
                "message", "model supplemental input",
                "customerId", "customer-42");
        WorkflowEvaluationReport report = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), activeCases, supplementalInput);

        assertThat(report.supplementalInput()).isEqualTo(supplementalInput);
        assertThat(report.caseResults())
                .extracting(WorkflowEvaluationCaseResult::caseId)
                .containsExactly(
                        "tracking-delay",
                        "missing-order-id-clarification",
                        "urge-shipping",
                        "return-or-exchange",
                        "compound-damage",
                        "vague-complaint",
                        "greeting-only",
                        "missing-order-result-tool-failure");
        assertThat(executions).hasValue(8);
    }

    @Test
    void schedulesAtMostTwelveRuntimeCasesInStableOrder() {
        AtomicInteger executions = new AtomicInteger();
        WorkflowRuntimeProperties properties = properties();
        properties.getEvaluation().setMaxCases(99);
        WorkflowRuntime runtime = (runId, plan, input) -> {
            executions.incrementAndGet();
            return passingResult("ok");
        };
        List<WorkflowEvaluationCase> cases = java.util.stream.IntStream.rangeClosed(1, 13)
                .mapToObj(index -> runtimeCase("case-" + index, List.of()))
                .toList();

        WorkflowEvaluationReport report = service(runtime, traceService(), budgetRegistry(), properties)
                .evaluate(candidate(), cases, null);

        assertThat(report.caseResults()).extracting(WorkflowEvaluationCaseResult::caseId)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 12)
                        .mapToObj(index -> "case-" + index)
                        .toList());
        assertThat(executions).hasValue(12);
    }

    @Test
    void rejectsWhenBoundedQueueIsFullAndCancelsSubmittedWork() {
        WorkflowRuntimeProperties properties = properties();
        properties.getEvaluation().setConcurrency(1);
        properties.getEvaluation().setQueueCapacity(1);
        properties.getEvaluation().setOverallDeadlineMs(100L);
        WorkflowRunBudgetRegistry budgetRegistry = budgetRegistry();
        when(budgetRegistry.cancel(anyString())).thenReturn(true);
        AtomicInteger active = new AtomicInteger();
        WorkflowRuntime runtime = (runId, plan, input) -> {
            active.incrementAndGet();
            try {
                Thread.sleep(5_000L);
                return passingResult("late");
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new WorkflowCanceledException(runId);
            }
            finally {
                active.decrementAndGet();
            }
        };
        WorkflowEvaluationService evaluationService = service(
                runtime, traceService(), budgetRegistry, properties);
        List<WorkflowEvaluationCase> cases = List.of(
                runtimeCase("case-1", List.of()),
                runtimeCase("case-2", List.of()),
                runtimeCase("case-3", List.of()));

        assertThatThrownBy(() -> evaluationService.evaluate(candidate(), cases, null))
                .isInstanceOf(RejectedExecutionException.class);

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(1))
                .untilAsserted(() -> assertThat(active).hasValue(0));
    }

    @Test
    void preDestroyCloseRejectsNewEvaluationSubmissions() throws Exception {
        WorkflowEvaluationService evaluationService = service(
                (runId, plan, input) -> passingResult("ok"),
                traceService(), budgetRegistry(), properties());

        assertThat(WorkflowEvaluationService.class.getMethod("close")
                .isAnnotationPresent(jakarta.annotation.PreDestroy.class)).isTrue();
        evaluationService.close();

        assertThatThrownBy(() -> evaluationService.evaluate(
                candidate(), List.of(runtimeCase("after-close", List.of())), null))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    void keepsCoreCasesStaticAndPreservesCompleteSupplementalInputForCoreOnlyEvaluation() {
        WorkflowRuleCatalog catalog = new WorkflowRuleCatalog();
        List<Map<String, Object>> receivedInputs = new ArrayList<>();
        WorkflowRuntime runtime = (runId, plan, input) -> {
            receivedInputs.add(input);
            return passingResult("Hello, how can I help?");
        };

        Map<String, Object> supplementalInput = Map.of(
                "message", "Hello",
                "locale", "en-AU");
        WorkflowEvaluationReport report = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), catalog.activePacks(null).getFirst().testCases(), supplementalInput);

        assertThat(report.caseResults())
                .extracting(WorkflowEvaluationCaseResult::caseId)
                .containsExactly(WorkflowEvaluationService.SUPPLEMENTAL_CASE_ID);
        assertThat(report.supplementalInput()).isEqualTo(supplementalInput);
        assertThat(receivedInputs).containsExactly(supplementalInput);
    }

    @Test
    void capsWorkersAtTwoAndPropagatesSecurityContextAndMdc() {
        setOwner("owner-a");
        MDC.put("requestId", "req-a");
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        Set<String> owners = ConcurrentHashMap.newKeySet();
        Set<String> requestIds = ConcurrentHashMap.newKeySet();
        WorkflowRuntime runtime = (runId, plan, input) -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            owners.add(SecurityIdentity.currentOwnerId());
            requestIds.add(MDC.get("requestId"));
            try {
                Thread.sleep(40L);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new WorkflowCanceledException(runId);
            }
            finally {
                active.decrementAndGet();
            }
            return passingResult("ok");
        };
        WorkflowRuntimeProperties properties = properties();
        properties.getEvaluation().setConcurrency(7);
        List<WorkflowEvaluationCase> cases = List.of(
                runtimeCase("case-1", List.of()),
                runtimeCase("case-2", List.of()),
                runtimeCase("case-3", List.of()),
                runtimeCase("case-4", List.of()));
        WorkflowEvaluationService evaluationService = service(
                runtime, traceService(), budgetRegistry(), properties);

        evaluationService.evaluate(candidate(), cases, null);

        setOwner("owner-b");
        MDC.put("requestId", "req-b");
        evaluationService.evaluate(candidate(), List.of(runtimeCase("case-5", List.of())), null);

        assertThat(maximum).hasValue(2);
        assertThat(owners).containsExactlyInAnyOrder("owner-a", "owner-b");
        assertThat(requestIds).containsExactlyInAnyOrder("req-a", "req-b");
    }

    @Test
    void workerOwnsTraceBudgetRuntimeAndCloseLifecycle() {
        TraceService traceService = traceService();
        WorkflowRunBudgetRegistry budgetRegistry = budgetRegistry();
        List<String> lifecycle = java.util.Collections.synchronizedList(new ArrayList<>());
        doAnswer(invocation -> {
            lifecycle.add("open:" + Thread.currentThread().getName());
            return null;
        }).when(budgetRegistry).open(anyString(), anyInt(), anyLong());
        doAnswer(invocation -> {
            lifecycle.add("close:" + Thread.currentThread().getName());
            return null;
        }).when(budgetRegistry).close(anyString());
        WorkflowRuntime runtime = (runId, plan, input) -> {
            lifecycle.add("runtime:" + Thread.currentThread().getName());
            return passingResult("ok");
        };

        service(runtime, traceService, budgetRegistry, properties())
                .evaluate(candidate(), List.of(runtimeCase("case-1", List.of())), null);

        assertThat(lifecycle).hasSize(3);
        assertThat(lifecycle).allMatch(entry -> entry.contains("workflow-evaluation-"));
        verify(budgetRegistry).open(anyString(), eq(1000), eq(90_000L));
        verify(budgetRegistry).close(anyString());
    }

    @Test
    void retriesProviderFailureOnceWithIdenticalCandidateAndRecordsBothRunIds() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowRuntime runtime = (runId, plan, input) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new BusinessException("ALIBABA_LLM_UNAVAILABLE", "provider unavailable");
            }
            return passingResult("resolved");
        };

        WorkflowEvaluationCaseResult result = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), List.of(runtimeCase("retry-provider", List.of())), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.PASSED);
        assertThat(result.attemptRunIds()).containsExactly("run-1", "run-2");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void assertionFailureIsDesignOriginAndDoesNotRetry() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowRuntime runtime = (runId, plan, input) -> {
            attempts.incrementAndGet();
            return new WorkflowRuntime.WorkflowExecutionResult(
                    Map.of("internal", "raw"),
                    List.of(new WorkflowStepSummary("end", "end", "SUCCEEDED", Map.of("internal", "raw"))));
        };
        WorkflowEvaluationAssertion assertion = new WorkflowEvaluationAssertion(
                "readable", WorkflowEvaluationAssertionType.FINAL_OUTPUT_CUSTOMER_READABLE, List.of(), null, null);

        WorkflowEvaluationCaseResult result = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), List.of(runtimeCase("design-failure", List.of(assertion))), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.DESIGN_FAILED);
        assertThat(result.errorOrigin()).isEqualTo(WorkflowEvaluationErrorOrigin.DESIGN);
        assertThat(result.assertions()).extracting(WorkflowEvaluationAssertionResult::status)
                .containsExactly(WorkflowEvaluationAssertionStatus.FAILED);
        assertThat(result.attemptRunIds()).containsExactly("run-1");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void evaluatesPathToolParsedOutputAndForbiddenClaimAssertionsDeterministically() {
        ToolExecutionLog toolLog = ToolExecutionLog.success(
                "queryOrderAPI",
                Map.of("userQuery", "99999999999"),
                Map.of("found", false),
                Instant.now(),
                Instant.now(),
                null);
        Map<String, Object> finalOutput = Map.of("answer", "I could not find that order. Please verify the order ID.");
        WorkflowRuntime runtime = (runId, plan, input) -> new WorkflowRuntime.WorkflowExecutionResult(
                finalOutput,
                List.of(
                        new WorkflowStepSummary("start", "start", "SUCCEEDED", input),
                        new WorkflowStepSummary("classify", "llm", "SUCCEEDED",
                                Map.of("parsed", Map.of("intent", "order_query", "confidence", 0.99))),
                        new WorkflowStepSummary("lookup", "tool", "SUCCEEDED", toolLog),
                        new WorkflowStepSummary("end", "end", "SUCCEEDED", finalOutput)));
        List<WorkflowEvaluationAssertion> assertions = List.of(
                assertion("path", WorkflowEvaluationAssertionType.PATH_NODE_TYPES_INCLUDE_IN_ORDER,
                        List.of("start", "llm", "tool", "end")),
                assertion("tool", WorkflowEvaluationAssertionType.TOOL_SUCCEEDED, List.of("queryOrderAPI")),
                assertion("parsed", WorkflowEvaluationAssertionType.PARSED_FIELDS_INCLUDE,
                        List.of("intent", "confidence")),
                assertion("readable", WorkflowEvaluationAssertionType.FINAL_OUTPUT_CUSTOMER_READABLE, List.of()),
                assertion("clarifies", WorkflowEvaluationAssertionType.FINAL_OUTPUT_CONTAINS_ANY,
                        List.of("verify", "order ID")),
                assertion("no-fabrication", WorkflowEvaluationAssertionType.FINAL_OUTPUT_EXCLUDES,
                        List.of("refund succeeded", "shipment confirmed")),
                new WorkflowEvaluationAssertion(
                        "missing-result",
                        WorkflowEvaluationAssertionType.TOOL_OUTPUT_FIELD_EQUALS,
                        List.of("queryOrderAPI"),
                        "found",
                        false));

        WorkflowEvaluationCaseResult result = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), List.of(runtimeCase("structured", assertions)), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.PASSED);
        assertThat(result.executedPath()).containsExactly("start", "classify", "lookup", "end");
        assertThat(result.assertions()).extracting(WorkflowEvaluationAssertionResult::status)
                .containsOnly(WorkflowEvaluationAssertionStatus.PASSED);
        assertThat(result.outputSummary()).isEqualTo("I could not find that order. Please verify the order ID.");
        assertThat(result.output()).isEqualTo(finalOutput);
    }

    @Test
    void rejectsFabricatedToolSuccessEmbeddedInLlmOutput() {
        WorkflowRuntime runtime = (runId, plan, input) -> new WorkflowRuntime.WorkflowExecutionResult(
                Map.of("answer", "Order lookup succeeded"),
                List.of(new WorkflowStepSummary(
                        "llm_fake_tool",
                        "llm",
                        "SUCCEEDED",
                        Map.of("toolName", "queryOrderAPI", "succeeded", true))));
        WorkflowEvaluationAssertion assertion = assertion(
                "real-tool-success",
                WorkflowEvaluationAssertionType.TOOL_SUCCEEDED,
                List.of("queryOrderAPI"));

        WorkflowEvaluationCaseResult result = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), List.of(runtimeCase("fake-tool", List.of(assertion))), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.DESIGN_FAILED);
        assertThat(result.assertions()).singleElement().satisfies(assertionResult ->
                assertThat(assertionResult.status()).isEqualTo(WorkflowEvaluationAssertionStatus.FAILED));
    }

    @Test
    void rejectsCustomerAnswerThatContainsInternalJsonAfterTextPrefix() {
        WorkflowRuntime runtime = (runId, plan, input) -> passingResult(
                "Result: {\"intent\":\"refund\",\"confidence\":0.9}");
        WorkflowEvaluationAssertion assertion = assertion(
                "customer-readable",
                WorkflowEvaluationAssertionType.FINAL_OUTPUT_CUSTOMER_READABLE,
                List.of());

        WorkflowEvaluationCaseResult result = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), List.of(runtimeCase("prefixed-json", List.of(assertion))), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.DESIGN_FAILED);
    }

    @Test
    void rejectsCustomerAnswerThatLeaksEvaluationFailureMarker() {
        WorkflowRuntime runtime = (runId, plan, input) -> passingResult(
                "Lookup failed: WORKFLOW_EVALUATION_TOOL_FAILURE. Please try later.");
        WorkflowEvaluationAssertion assertion = assertion(
                "customer-readable",
                WorkflowEvaluationAssertionType.FINAL_OUTPUT_CUSTOMER_READABLE,
                List.of());

        WorkflowEvaluationCaseResult result = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), List.of(runtimeCase("fixture-leak", List.of(assertion))), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.DESIGN_FAILED);
    }

    @Test
    void deterministicToolPolicyFailureIsDesignAndDoesNotRetry() {
        AtomicInteger attempts = new AtomicInteger();
        WorkflowRuntime runtime = (runId, plan, input) -> {
            attempts.incrementAndGet();
            throw new BusinessException("WORKFLOW_TOOL_NOT_ALLOWED", "tool is outside allowedTools");
        };

        WorkflowEvaluationCaseResult result = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), List.of(runtimeCase("tool-policy", List.of())), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.DESIGN_FAILED);
        assertThat(result.errorOrigin()).isEqualTo(WorkflowEvaluationErrorOrigin.DESIGN);
        assertThat(result.attemptRunIds()).containsExactly("run-1");
        assertThat(attempts).hasValue(1);
    }

    @Test
    void runtimeCaseInjectsUnforgeableToolFailureFixtureAndAssertsFailedToolEvidence() {
        AtomicInteger fixtureCalls = new AtomicInteger();
        WorkflowRuntime runtime = (runId, plan, input) -> {
            ToolExecutionLog failedLog = WorkflowEvaluationFixtures.failedToolCall(
                            input, "queryOrderAPI", Map.of("userQuery", "20260630003"))
                    .orElseThrow();
            fixtureCalls.incrementAndGet();
            Map<String, Object> output = Map.of("answer", "Order lookup is unavailable. Please try later.");
            return new WorkflowRuntime.WorkflowExecutionResult(
                    output,
                    List.of(
                            new WorkflowStepSummary("lookup", "tool", "SUCCEEDED", failedLog),
                            new WorkflowStepSummary("end", "end", "SUCCEEDED", output)));
        };
        WorkflowEvaluationCase workflowCase = new WorkflowEvaluationCase(
                "forced-tool-failure",
                "Force the order lookup to fail",
                "The workflow follows its explicit failure fallback.",
                WorkflowEvaluationCaseKind.RUNTIME,
                Map.of("message", "Please urge shipment for order 20260630003"),
                List.of(assertion("tool-failed", WorkflowEvaluationAssertionType.TOOL_FAILED,
                        List.of("queryOrderAPI"))),
                new WorkflowEvaluationFixture("queryOrderAPI"));

        WorkflowEvaluationCaseResult result = service(runtime, traceService(), budgetRegistry(), properties())
                .evaluate(candidate(), List.of(workflowCase), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.PASSED);
        assertThat(fixtureCalls).hasValue(1);
        assertThat(result.assertions()).singleElement().satisfies(assertionResult ->
                assertThat(assertionResult.status()).isEqualTo(WorkflowEvaluationAssertionStatus.PASSED));
    }

    @Test
    void retriesHarnessFailureAndPreservesPartialTracePathFromFinalAttempt() {
        TraceService traceService = traceService();
        when(traceService.listSteps("run-2")).thenReturn(List.of(
                step("run-2", "workflow_node_start"),
                step("run-2", "workflow_node_lookup")));
        WorkflowRuntime runtime = (runId, plan, input) -> {
            throw new IllegalStateException("harness failed");
        };

        WorkflowEvaluationCaseResult result = service(runtime, traceService, budgetRegistry(), properties())
                .evaluate(candidate(), List.of(runtimeCase("harness", List.of())), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.INFRA_ERROR);
        assertThat(result.errorOrigin()).isEqualTo(WorkflowEvaluationErrorOrigin.HARNESS);
        assertThat(result.attemptRunIds()).containsExactly("run-1", "run-2");
        assertThat(result.executedPath()).containsExactly("start", "lookup");
    }

    @Test
    void retriesSameCandidateWhenTraceRunCreationHasTransientHarnessFailure() {
        TraceService traceService = traceService();
        when(traceService.startRun(eq(RunType.WORKFLOW), any()))
                .thenThrow(new IllegalStateException("trace store unavailable"))
                .thenReturn(new TraceRun("run-retry", Instant.now()));
        AtomicInteger runtimeCalls = new AtomicInteger();
        WorkflowRuntime runtime = (runId, plan, input) -> {
            runtimeCalls.incrementAndGet();
            return passingResult("recovered");
        };

        WorkflowEvaluationCaseResult result = service(runtime, traceService, budgetRegistry(), properties())
                .evaluate(candidate(), List.of(runtimeCase("trace-retry", List.of())), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.PASSED);
        assertThat(result.attemptRunIds()).containsExactly("run-retry");
        assertThat(runtimeCalls).hasValue(1);
        verify(traceService, times(2)).startRun(eq(RunType.WORKFLOW), any());
    }

    @Test
    void coordinatorCancelsExpiredCaseWithoutClosingWorkerBudget() {
        WorkflowRuntimeProperties properties = properties();
        properties.getEvaluation().setCaseDeadlineMs(30L);
        properties.getEvaluation().setOverallDeadlineMs(1_000L);
        WorkflowRunBudgetRegistry budgetRegistry = budgetRegistry();
        when(budgetRegistry.cancel(anyString())).thenReturn(true);
        WorkflowRuntime runtime = (runId, plan, input) -> {
            try {
                Thread.sleep(5_000L);
                return passingResult("late");
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new WorkflowCanceledException(runId);
            }
        };

        WorkflowEvaluationCaseResult result = service(runtime, traceService(), budgetRegistry, properties)
                .evaluate(candidate(), List.of(runtimeCase("slow", List.of())), null)
                .caseResults().getFirst();

        assertThat(result.status()).isEqualTo(WorkflowEvaluationCaseStatus.CANCELED);
        assertThat(result.errorOrigin()).isEqualTo(WorkflowEvaluationErrorOrigin.CANCELED);
        verify(budgetRegistry).cancel("run-1");
        verify(budgetRegistry, timeout(1_000L)).close("run-1");
    }

    private WorkflowEvaluationService service(WorkflowRuntime runtime, TraceService traceService,
            WorkflowRunBudgetRegistry budgetRegistry, WorkflowRuntimeProperties properties) {
        WorkflowEvaluationService service = new WorkflowEvaluationService(
                budgetRegistry, runtime, traceService, properties, new ObjectMapper().findAndRegisterModules());
        services.add(service);
        return service;
    }

    private TraceService traceService() {
        TraceService traceService = mock(TraceService.class);
        AtomicInteger runIds = new AtomicInteger();
        when(traceService.startRun(eq(RunType.WORKFLOW), any())).thenAnswer(invocation ->
                new TraceRun("run-" + runIds.incrementAndGet(), Instant.now()));
        when(traceService.listSteps(anyString())).thenReturn(List.of());
        return traceService;
    }

    private WorkflowRunBudgetRegistry budgetRegistry() {
        return mock(WorkflowRunBudgetRegistry.class);
    }

    private WorkflowRuntimeProperties properties() {
        return new WorkflowRuntimeProperties();
    }

    private WorkflowEvaluationCase runtimeCase(String id, List<WorkflowEvaluationAssertion> assertions) {
        return new WorkflowEvaluationCase(
                id,
                "Prompt " + id,
                "Expected " + id,
                WorkflowEvaluationCaseKind.RUNTIME,
                Map.of("message", "Input " + id),
                assertions);
    }

    private WorkflowEvaluationAssertion assertion(String id, WorkflowEvaluationAssertionType type,
            List<String> values) {
        return new WorkflowEvaluationAssertion(id, type, values, null, null);
    }

    private WorkflowExecutionPlan candidate() {
        WorkflowNode start = new WorkflowNode("start", "start", Map.of());
        WorkflowNode end = new WorkflowNode("end", "end", Map.of());
        return new WorkflowExecutionPlan(
                start,
                end,
                Map.of("start", start, "end", end),
                Map.of(),
                Map.of(),
                List.of(start, end),
                List.of(),
                List.of(),
                Set.of());
    }

    private static WorkflowRuntime.WorkflowExecutionResult passingResult(String answer) {
        Map<String, Object> output = Map.of("answer", answer);
        return new WorkflowRuntime.WorkflowExecutionResult(
                output,
                List.of(new WorkflowStepSummary("end", "end", "SUCCEEDED", output)));
    }

    private RunStepResponse step(String runId, String nodeName) {
        return new RunStepResponse(
                "step-" + nodeName,
                runId,
                nodeName,
                "{}",
                "{}",
                null,
                StepStatus.SUCCEEDED,
                Instant.now(),
                Instant.now());
    }

    private void setOwner(String ownerId) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(ownerId, "n/a", List.of()));
        SecurityContextHolder.setContext(context);
    }
}
