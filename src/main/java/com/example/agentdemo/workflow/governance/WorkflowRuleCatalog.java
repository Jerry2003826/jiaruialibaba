package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class WorkflowRuleCatalog {

    private static final List<String> DEFAULT_RESOURCE_PATHS = List.of(
            "workflow-builder/rules/core.json",
            "workflow-builder/rules/customer-service-ecommerce.json");
    private static final Set<String> ALLOWED_SEVERITIES = Set.of("info", "warning", "error");
    private static final String CORE_PACK_ID = "core";
    private static final String CUSTOMER_SERVICE_DOMAIN = "customer-service-ecommerce";
    private static final Set<String> CUSTOMER_SERVICE_ECOMMERCE_EXCLUSIONS = Set.of(
            "不是电商客服", "非电商客服", "不是电商", "非电商场景", "不属于电商",
            "not an e-commerce", "not an ecommerce", "not e-commerce", "not ecommerce");
    private static final Set<String> CUSTOMER_SERVICE_ECOMMERCE_SIGNALS = Set.of(
            "客服", "售后", "投诉", "工单",
            "订单", "订单号", "物流", "发货", "催发货", "退款", "退货", "换货", "补发", "配送",
            "商品", "破损", "会员",
            "crm", "vip", "order", "logistics", "delivery", "shipping", "shipment", "tracking", "refund",
            "return", "exchange", "damaged product", "membership", "customer service", "support ticket",
            "customer-service-ecommerce");

    private final List<WorkflowRulePack> packs;

    public WorkflowRuleCatalog() {
        this(loadFromResources(DEFAULT_RESOURCE_PATHS));
    }

    WorkflowRuleCatalog(List<WorkflowRulePack> packs) {
        this.packs = validateAndFreeze(packs);
    }

    public List<WorkflowRulePack> activePacks(String domain) {
        WorkflowRulePack core = findCorePack();
        String normalizedDomain = normalize(domain);
        if (normalizedDomain == null) {
            return List.of(core);
        }
        return packs.stream()
                .filter(pack -> CORE_PACK_ID.equals(pack.id())
                        || pack.domains().stream().map(this::normalize).anyMatch(normalizedDomain::equals))
                .toList();
    }

    public String detectDomain(WorkflowDefinition definition) {
        if (definition == null) {
            return null;
        }
        return detectDomainText(flattenDefinition(definition));
    }

    String detectDomainText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        if (containsAny(haystack, CUSTOMER_SERVICE_ECOMMERCE_EXCLUSIONS)) {
            return null;
        }
        if (containsAny(haystack, CUSTOMER_SERVICE_ECOMMERCE_SIGNALS)) {
            return CUSTOMER_SERVICE_DOMAIN;
        }
        return null;
    }

    public List<WorkflowRulePack> allPacks() {
        return packs;
    }

    private WorkflowRulePack findCorePack() {
        return packs.stream()
                .filter(pack -> CORE_PACK_ID.equals(pack.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing core workflow rule pack"));
    }

    private String flattenDefinition(WorkflowDefinition definition) {
        StringBuilder builder = new StringBuilder();
        for (WorkflowNode node : definition.nodes()) {
            append(builder, node.id());
            append(builder, node.type());
            append(builder, node.label());
            append(builder, node.route());
            appendValue(builder, node.config());
        }
        for (WorkflowEdge edge : definition.edges()) {
            append(builder, edge.from());
            append(builder, edge.to());
            append(builder, edge.condition());
            append(builder, edge.label());
            append(builder, edge.route());
        }
        return builder.toString();
    }

    private void appendValue(StringBuilder builder, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, childValue) -> {
                append(builder, String.valueOf(key));
                appendValue(builder, childValue);
            });
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object childValue : iterable) {
                appendValue(builder, childValue);
            }
            return;
        }
        append(builder, String.valueOf(value));
    }

    private void append(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        builder.append(value).append('\n');
    }

    private static List<WorkflowRulePack> loadFromResources(List<String> resourcePaths) {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<WorkflowRulePack> loadedPacks = new ArrayList<>();
        for (String resourcePath : resourcePaths) {
            loadedPacks.add(loadPack(objectMapper, resourcePath));
        }
        return loadedPacks;
    }

    private static WorkflowRulePack loadPack(ObjectMapper objectMapper, String resourcePath) {
        try (InputStream stream = WorkflowRuleCatalog.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Workflow rule pack resource not found: " + resourcePath);
            }
            return objectMapper.readValue(stream, WorkflowRulePack.class);
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to load workflow rule pack resource: " + resourcePath, exception);
        }
    }

    private List<WorkflowRulePack> validateAndFreeze(List<WorkflowRulePack> rawPacks) {
        if (rawPacks == null || rawPacks.isEmpty()) {
            throw new IllegalStateException("Workflow rule packs must not be empty");
        }

        List<WorkflowRulePack> validatedPacks = rawPacks.stream()
                .filter(Objects::nonNull)
                .map(this::validatePack)
                .collect(Collectors.toCollection(ArrayList::new));

        ensureUniqueIds(validatedPacks.stream().map(WorkflowRulePack::id).toList(), "pack");
        ensureUniqueIds(validatedPacks.stream()
                .flatMap(pack -> pack.rules().stream())
                .map(WorkflowGovernanceRule::id)
                .toList(), "rule");
        ensureUniqueIds(validatedPacks.stream()
                .flatMap(pack -> pack.testCases().stream())
                .map(WorkflowEvaluationCase::id)
                .toList(), "evaluation case");

        boolean hasCore = validatedPacks.stream().anyMatch(pack -> CORE_PACK_ID.equals(pack.id()));
        if (!hasCore) {
            throw new IllegalStateException("Workflow rule packs must include core");
        }

        validatedPacks.sort((left, right) -> {
            if (CORE_PACK_ID.equals(left.id())) {
                return -1;
            }
            if (CORE_PACK_ID.equals(right.id())) {
                return 1;
            }
            return 0;
        });
        return List.copyOf(validatedPacks);
    }

    private WorkflowRulePack validatePack(WorkflowRulePack pack) {
        String packId = requireNonBlank(pack.id(), "Workflow rule pack id must not be blank");
        String version = requireNonBlank(pack.version(), "Workflow rule pack version must not be blank");
        List<String> domains = pack.domains().stream()
                .map(domain -> requireNonBlank(domain, "Workflow rule pack domain must not be blank"))
                .toList();
        List<WorkflowGovernanceRule> rules = pack.rules().stream()
                .map(this::validateRule)
                .toList();
        List<String> knowledgeEntries = pack.knowledgeEntries().stream()
                .map(entry -> requireNonBlank(entry, "Workflow knowledge entry must not be blank"))
                .toList();
        List<WorkflowEvaluationCase> testCases = pack.testCases().stream()
                .map(this::validateCase)
                .toList();
        return new WorkflowRulePack(packId, version, domains, rules, knowledgeEntries, testCases);
    }

    private WorkflowGovernanceRule validateRule(WorkflowGovernanceRule rule) {
        String severity = requireNonBlank(rule.severity(), "Workflow rule severity must not be blank")
                .toLowerCase(Locale.ROOT);
        if (!ALLOWED_SEVERITIES.contains(severity)) {
            throw new IllegalStateException("Unknown workflow rule severity: " + rule.severity());
        }
        return new WorkflowGovernanceRule(
                requireNonBlank(rule.id(), "Workflow rule id must not be blank"),
                severity,
                requireNonBlank(rule.title(), "Workflow rule title must not be blank"),
                requireNonBlank(rule.description(), "Workflow rule description must not be blank"),
                requireNonEmptyEntries(rule.antiPatterns(),
                        "Workflow rule anti-patterns must not be empty",
                        "Workflow rule anti-pattern must not be blank"),
                requireNonEmptyEntries(rule.examples(),
                        "Workflow rule examples must not be empty",
                        "Workflow rule example must not be blank"),
                requireNonBlank(rule.repairHint(), "Workflow rule repair hint must not be blank"),
                requireNonBlank(rule.detector(), "Workflow rule detector must not be blank"));
    }

    private WorkflowEvaluationCase validateCase(WorkflowEvaluationCase workflowCase) {
        String caseId = requireNonBlank(workflowCase.id(), "Workflow evaluation case id must not be blank");
        WorkflowEvaluationCaseKind kind = Objects.requireNonNull(workflowCase.kind(),
                "Workflow evaluation case kind must not be null");
        Map<String, Object> runtimeInput = workflowCase.runtimeInput();
        List<WorkflowEvaluationAssertion> assertions = workflowCase.assertions().stream()
                .map(assertion -> validateAssertion(caseId, assertion))
                .toList();
        if (kind == WorkflowEvaluationCaseKind.RUNTIME) {
            if (runtimeInput.isEmpty()) {
                throw new IllegalStateException("Runtime workflow evaluation case input must not be empty: " + caseId);
            }
            if (assertions.isEmpty()) {
                throw new IllegalStateException("Runtime workflow evaluation case assertions must not be empty: " + caseId);
            }
        }
        WorkflowEvaluationFixture fixture = workflowCase.fixture();
        if (fixture != null) {
            fixture = new WorkflowEvaluationFixture(requireNonBlank(
                    fixture.failTool(), "Workflow evaluation fixture failTool must not be blank: " + caseId));
        }
        return new WorkflowEvaluationCase(
                caseId,
                requireNonBlank(workflowCase.prompt(), "Workflow evaluation case prompt must not be blank"),
                requireNonBlank(workflowCase.expectedBehavior(),
                        "Workflow evaluation case expected behavior must not be blank"),
                kind,
                runtimeInput,
                assertions,
                fixture);
    }

    private WorkflowEvaluationAssertion validateAssertion(String caseId, WorkflowEvaluationAssertion assertion) {
        if (assertion == null) {
            throw new IllegalStateException("Workflow evaluation assertion must not be null: " + caseId);
        }
        String assertionId = requireNonBlank(assertion.id(),
                "Workflow evaluation assertion id must not be blank: " + caseId);
        WorkflowEvaluationAssertionType type = Objects.requireNonNull(assertion.type(),
                "Workflow evaluation assertion type must not be null: " + caseId + "/" + assertionId);
        List<String> values = assertion.values().stream()
                .map(value -> requireNonBlank(value,
                        "Workflow evaluation assertion value must not be blank: " + caseId + "/" + assertionId))
                .toList();
        String field = normalize(assertion.field());
        if (type == WorkflowEvaluationAssertionType.TOOL_OUTPUT_FIELD_EQUALS) {
            if (values.size() != 1 || field == null || assertion.expectedValue() == null) {
                throw new IllegalStateException(
                        "Tool output assertion requires one tool name, field, and expected value: "
                                + caseId + "/" + assertionId);
            }
        }
        return new WorkflowEvaluationAssertion(assertionId, type, values, field, assertion.expectedValue());
    }

    private void ensureUniqueIds(List<String> ids, String type) {
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                throw new IllegalStateException("Duplicate workflow " + type + " id: " + id);
            }
        }
    }

    private List<String> requireNonEmptyEntries(List<String> values, String emptyMessage, String blankMessage) {
        if (values == null || values.isEmpty()) {
            throw new IllegalStateException(emptyMessage);
        }
        return values.stream()
                .map(value -> requireNonBlank(value, blankMessage))
                .toList();
    }

    private String requireNonBlank(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalStateException(message);
        }
        return normalized;
    }

    private boolean containsAny(String haystack, Set<String> signals) {
        return signals.stream()
                .map(signal -> signal.toLowerCase(Locale.ROOT))
                .anyMatch(haystack::contains);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
