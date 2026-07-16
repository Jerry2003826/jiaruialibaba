package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.workflow.governance.WorkflowBuilderContext;
import com.example.agentdemo.workflow.governance.WorkflowBuilderContextService;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCase;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseKind;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseResult;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationCaseStatus;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationAssertion;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationAssertionType;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationReport;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationService;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceFinding;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceReport;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceService;
import com.example.agentdemo.workflow.governance.WorkflowRuleCatalog;
import com.example.agentdemo.workflow.governance.WorkflowRulePack;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowGovernanceOrchestrator {

    private static final int MAX_LOCKED_SPEC_CASES = 4;

    private final WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer;
    private final WorkflowCompiler workflowCompiler;
    private final WorkflowDefinitionContractValidator contractValidator;
    private final WorkflowBuilderContextService builderContextService;
    private final WorkflowGovernanceService governanceService;
    private final WorkflowEvaluationService evaluationService;
    private final WorkflowRuleCatalog ruleCatalog;
    private final ObjectMapper objectMapper;

    public WorkflowGovernanceOrchestrator(
            WorkflowStructuredOutputAutoconfigurer structuredOutputAutoconfigurer,
            WorkflowCompiler workflowCompiler,
            WorkflowDefinitionContractValidator contractValidator,
            WorkflowBuilderContextService builderContextService,
            WorkflowGovernanceService governanceService,
            WorkflowEvaluationService evaluationService,
            WorkflowRuleCatalog ruleCatalog,
            ObjectMapper objectMapper) {
        this.structuredOutputAutoconfigurer = structuredOutputAutoconfigurer;
        this.workflowCompiler = workflowCompiler;
        this.contractValidator = contractValidator;
        this.builderContextService = builderContextService;
        this.governanceService = governanceService;
        this.evaluationService = evaluationService;
        this.ruleCatalog = ruleCatalog;
        this.objectMapper = objectMapper;
    }

    public WorkflowGovernanceEvaluationResponse evaluate(WorkflowDefinition definition, Object lockedSpec,
            Map<String, Object> supplementalInput) {
        Map<String, Object> normalizedInput = copyInput(supplementalInput);
        try {
            WorkflowBuilderContext context = builderContextService.build(null, lockedSpecText(lockedSpec), null);
            return evaluate(definition, context, normalizedInput, List.of());
        }
        catch (RuntimeException exception) {
            return infrastructureFailure(definition, normalizedInput, List.of());
        }
    }

    public WorkflowGovernanceEvaluationResponse evaluate(WorkflowDefinition definition,
            WorkflowBuilderContext context,
            Map<String, Object> supplementalInput,
            List<WorkflowGovernanceFinding> additionalStaticFindings) {
        Map<String, Object> normalizedInput = copyInput(supplementalInput);
        List<WorkflowRulePack> activePacks;
        try {
            activePacks = activePacks(definition, context);
        }
        catch (RuntimeException exception) {
            return infrastructureFailure(definition, normalizedInput, List.of());
        }

        WorkflowDefinition normalizedDefinition;
        WorkflowExecutionPlan executionPlan;
        WorkflowGovernanceReport governanceReport;
        try {
            normalizedDefinition = structuredOutputAutoconfigurer.apply(definition);
            contractValidator.validate(normalizedDefinition);
            executionPlan = workflowCompiler.compile(normalizedDefinition);
            governanceReport = governanceService.evaluateStatic(normalizedDefinition, context);
        }
        catch (RuntimeException exception) {
            return validityFailure(definition, normalizedInput, activePacks, exception);
        }

        if (governanceReport.hasBlocks()) {
            return response(
                    WorkflowGenerationStatus.BLOCKED,
                    normalizedDefinition,
                    governanceReport,
                    new WorkflowEvaluationReport(normalizedInput, List.of()),
                    activePacks);
        }
        governanceReport = mergeFindings(governanceReport, additionalStaticFindings);
        if (governanceReport.hasBlocks()) {
            return response(
                    WorkflowGenerationStatus.BLOCKED,
                    normalizedDefinition,
                    governanceReport,
                    new WorkflowEvaluationReport(normalizedInput, List.of()),
                    activePacks);
        }

        WorkflowEvaluationReport evaluationReport;
        try {
            List<WorkflowEvaluationCase> runtimeCases = evaluationCases(normalizedDefinition, context);
            if (runtimeCases.isEmpty() && normalizedInput.isEmpty()) {
                runtimeCases = List.of(coreRuntimeSmokeCase());
            }
            evaluationReport = evaluationService.evaluate(
                    executionPlan,
                    runtimeCases,
                    normalizedInput);
        }
        catch (RuntimeException exception) {
            return infrastructureFailure(normalizedDefinition, normalizedInput, activePacks);
        }

        if (evaluationReport.caseResults().isEmpty()) {
            governanceReport = mergeFindings(governanceReport, List.of(runtimeEvaluationRequiredFinding()));
            return response(
                    WorkflowGenerationStatus.BLOCKED,
                    normalizedDefinition,
                    governanceReport,
                    evaluationReport,
                    activePacks);
        }

        WorkflowGenerationStatus status = statusOf(evaluationReport.caseResults());
        return response(status, normalizedDefinition, governanceReport, evaluationReport, activePacks);
    }

    private WorkflowGovernanceFinding runtimeEvaluationRequiredFinding() {
        return new WorkflowGovernanceFinding(
                "core-runtime-evaluation-required",
                WorkflowGovernanceFinding.Severity.BLOCK,
                WorkflowGovernanceFinding.Phase.STATIC,
                "No runtime evaluation case was executed, so the workflow cannot be marked READY.",
                List.of(),
                "Add at least one locked-spec test case or provide a real supplemental input and run governance again.",
                Map.of("executedCases", 0));
    }

    private WorkflowEvaluationCase coreRuntimeSmokeCase() {
        return new WorkflowEvaluationCase(
                "core-runtime-smoke",
                "完成一次基础工作流运行测试",
                "The workflow reaches a successful runtime result using a standard user message.",
                WorkflowEvaluationCaseKind.RUNTIME,
                Map.of("message", "请完成一次基础工作流运行测试。"),
                List.of());
    }

    private WorkflowGovernanceEvaluationResponse validityFailure(WorkflowDefinition definition,
            Map<String, Object> supplementalInput,
            List<WorkflowRulePack> activePacks,
            RuntimeException exception) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("errorType", exception.getClass().getSimpleName());
        if (exception instanceof BusinessException businessException && businessException.getCode() != null) {
            evidence.put("errorCode", businessException.getCode());
        }
        WorkflowGovernanceFinding finding = new WorkflowGovernanceFinding(
                "core-workflow-validity",
                WorkflowGovernanceFinding.Severity.BLOCK,
                WorkflowGovernanceFinding.Phase.STATIC,
                messageOf(exception),
                List.of(),
                "Return one complete workflow that compiles against the registered node and variable contracts.",
                evidence);
        return response(
                WorkflowGenerationStatus.BLOCKED,
                definition,
                new WorkflowGovernanceReport(List.of(finding)),
                new WorkflowEvaluationReport(supplementalInput, List.of()),
                activePacks);
    }

    private WorkflowGovernanceEvaluationResponse infrastructureFailure(WorkflowDefinition definition,
            Map<String, Object> supplementalInput,
            List<WorkflowRulePack> activePacks) {
        return response(
                WorkflowGenerationStatus.INFRA_ERROR,
                definition,
                new WorkflowGovernanceReport(List.of()),
                new WorkflowEvaluationReport(supplementalInput, List.of()),
                activePacks);
    }

    private WorkflowGovernanceEvaluationResponse response(WorkflowGenerationStatus status,
            WorkflowDefinition definition,
            WorkflowGovernanceReport governanceReport,
            WorkflowEvaluationReport evaluationReport,
            List<WorkflowRulePack> activePacks) {
        List<WorkflowEvaluationCaseResult> testResults = evaluationReport.caseResults();
        List<WorkflowActiveRulePack> activeRulePacks = activePacks.stream()
                .map(pack -> new WorkflowActiveRulePack(pack.id(), pack.version()))
                .toList();
        return new WorkflowGovernanceEvaluationResponse(
                status,
                definition,
                governanceReport,
                evaluationReport,
                testResults,
                activeRulePacks);
    }

    private WorkflowGovernanceReport mergeFindings(WorkflowGovernanceReport report,
            List<WorkflowGovernanceFinding> additionalFindings) {
        if (additionalFindings == null || additionalFindings.isEmpty()) {
            return report;
        }
        List<WorkflowGovernanceFinding> findings = new ArrayList<>(report.findings());
        findings.addAll(additionalFindings);
        return new WorkflowGovernanceReport(findings);
    }

    private WorkflowGenerationStatus statusOf(List<WorkflowEvaluationCaseResult> results) {
        boolean infrastructureFailure = results.stream().anyMatch(result ->
                result.status() == WorkflowEvaluationCaseStatus.INFRA_ERROR
                        || result.status() == WorkflowEvaluationCaseStatus.CANCELED);
        if (infrastructureFailure) {
            return WorkflowGenerationStatus.INFRA_ERROR;
        }
        boolean designFailure = results.stream()
                .anyMatch(result -> result.status() == WorkflowEvaluationCaseStatus.DESIGN_FAILED);
        return designFailure ? WorkflowGenerationStatus.BLOCKED : WorkflowGenerationStatus.READY;
    }

    private List<WorkflowEvaluationCase> evaluationCases(WorkflowDefinition definition,
            WorkflowBuilderContext context) {
        Map<String, WorkflowEvaluationCase> casesById = new LinkedHashMap<>();
        if (context != null) {
            addEvaluationCases(casesById, context.activeRulePacks());
            addEvaluationCases(casesById, ruleCatalog.activePacks(context.domain()));
        }
        else {
            addEvaluationCases(casesById, ruleCatalog.activePacks(null));
        }
        String graphDomain = ruleCatalog.detectDomain(definition);
        if (graphDomain != null) {
            addEvaluationCases(casesById, ruleCatalog.activePacks(graphDomain));
        }
        lockedSpecCases(context).forEach(workflowCase ->
                casesById.putIfAbsent(workflowCase.id(), workflowCase));
        return List.copyOf(casesById.values());
    }

    private List<WorkflowEvaluationCase> lockedSpecCases(WorkflowBuilderContext context) {
        if (context == null || context.lockedSpec().isBlank()) {
            return List.of();
        }
        JsonNode testCases;
        try {
            JsonNode root = objectMapper.readTree(context.lockedSpec());
            testCases = root == null ? null : root.get("testCases");
        }
        catch (JsonProcessingException exception) {
            return List.of();
        }
        if (testCases == null || !testCases.isArray()) {
            return List.of();
        }

        List<WorkflowEvaluationCase> cases = new ArrayList<>();
        for (JsonNode sourceCase : testCases) {
            if (cases.size() >= MAX_LOCKED_SPEC_CASES) {
                break;
            }
            WorkflowEvaluationCase workflowCase = lockedSpecCase(sourceCase, cases.size() + 1);
            if (workflowCase != null) {
                cases.add(workflowCase);
            }
        }
        return List.copyOf(cases);
    }

    private WorkflowEvaluationCase lockedSpecCase(JsonNode sourceCase, int ordinal) {
        String prompt = "";
        Map<String, Object> runtimeInput = Map.of();
        if (sourceCase.isTextual()) {
            prompt = sourceCase.asText("").trim();
            if (!prompt.isBlank()) {
                runtimeInput = Map.of("message", prompt);
            }
        }
        else if (sourceCase.isObject()) {
            JsonNode promptNode = sourceCase.get("prompt");
            if (promptNode != null && promptNode.isTextual()) {
                prompt = promptNode.asText("").trim();
            }
            runtimeInput = lockedSpecRuntimeInput(sourceCase.get("input"));
            if (runtimeInput.isEmpty() && !prompt.isBlank()) {
                runtimeInput = Map.of("message", prompt);
            }
        }
        if (runtimeInput.isEmpty()) {
            return null;
        }
        if (prompt.isBlank()) {
            prompt = String.valueOf(runtimeInput.getOrDefault("message", "Locked specification test " + ordinal));
        }

        String caseId = "locked-spec-" + ordinal;
        WorkflowEvaluationAssertion readableOutput = new WorkflowEvaluationAssertion(
                caseId + "-customer-readable-output",
                WorkflowEvaluationAssertionType.FINAL_OUTPUT_CUSTOMER_READABLE,
                List.of(),
                null,
                null);
        return new WorkflowEvaluationCase(
                caseId,
                prompt,
                "The locked specification input produces a customer-readable final output.",
                WorkflowEvaluationCaseKind.RUNTIME,
                runtimeInput,
                List.of(readableOutput));
    }

    private Map<String, Object> lockedSpecRuntimeInput(JsonNode inputNode) {
        if (inputNode == null || inputNode.isNull()) {
            return Map.of();
        }
        if (inputNode.isTextual()) {
            String message = inputNode.asText("").trim();
            return message.isBlank() ? Map.of() : Map.of("message", message);
        }
        if (!inputNode.isObject()) {
            return Map.of();
        }
        Map<String, Object> converted = objectMapper.convertValue(inputNode, new com.fasterxml.jackson.core.type.TypeReference<>() {
        });
        LinkedHashMap<String, Object> safeInput = new LinkedHashMap<>();
        converted.forEach((key, value) -> {
            if (key != null && value != null) {
                safeInput.put(key, value);
            }
        });
        return Map.copyOf(safeInput);
    }

    private void addEvaluationCases(Map<String, WorkflowEvaluationCase> casesById,
            List<WorkflowRulePack> packs) {
        if (packs == null) {
            return;
        }
        packs.stream()
                .flatMap(pack -> pack.testCases().stream())
                .filter(workflowCase -> workflowCase.kind() == WorkflowEvaluationCaseKind.RUNTIME)
                .forEach(workflowCase -> casesById.putIfAbsent(workflowCase.id(), workflowCase));
    }

    private List<WorkflowRulePack> activePacks(WorkflowDefinition definition, WorkflowBuilderContext context) {
        Map<String, WorkflowRulePack> packsById = new LinkedHashMap<>();
        if (context != null) {
            context.activeRulePacks().forEach(pack -> packsById.putIfAbsent(pack.id(), pack));
            ruleCatalog.activePacks(context.domain()).forEach(pack -> packsById.putIfAbsent(pack.id(), pack));
        }
        else {
            ruleCatalog.activePacks(null).forEach(pack -> packsById.putIfAbsent(pack.id(), pack));
        }
        String graphDomain = ruleCatalog.detectDomain(definition);
        if (graphDomain != null) {
            ruleCatalog.activePacks(graphDomain).forEach(pack -> packsById.putIfAbsent(pack.id(), pack));
        }
        return List.copyOf(packsById.values());
    }

    private String lockedSpecText(Object lockedSpec) {
        if (lockedSpec == null) {
            return "";
        }
        if (lockedSpec instanceof JsonNode node && node.isTextual()) {
            return node.asText().trim();
        }
        if (lockedSpec instanceof String text) {
            return text.trim();
        }
        try {
            return objectMapper.writeValueAsString(lockedSpec);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("lockedSpec cannot be serialized", exception);
        }
    }

    private Map<String, Object> copyInput(Map<String, Object> input) {
        if (input == null) {
            return Map.of();
        }
        LinkedHashMap<String, Object> copiedInput = new LinkedHashMap<>(input);
        copiedInput.forEach((key, value) -> {
            if (key == null || value == null) {
                throw new BusinessException(
                        "VALIDATION_ERROR",
                        "supplementalInput must not contain null keys or values");
            }
        });
        return Map.copyOf(copiedInput);
    }

    private String messageOf(RuntimeException exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
