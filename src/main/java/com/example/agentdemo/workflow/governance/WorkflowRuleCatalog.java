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
    private static final Set<String> CUSTOMER_SERVICE_KEYWORDS = Set.of(
            "customer service", "customer-support", "customer_support", "客服", "售后", "投诉",
            "订单", "物流", "退货", "换货", "退款", "催发货", "补发", "crm", "vip",
            "queryorderapi", "logistics", "shipping", "order lookup", "return", "exchange");

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
        String haystack = flattenDefinition(definition).toLowerCase(Locale.ROOT);
        long matches = CUSTOMER_SERVICE_KEYWORDS.stream()
                .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                .filter(haystack::contains)
                .count();
        if (haystack.contains("queryorderapi") || matches >= 2) {
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
                requireNonBlank(rule.detector(), "Workflow rule detector must not be blank"));
    }

    private WorkflowEvaluationCase validateCase(WorkflowEvaluationCase workflowCase) {
        return new WorkflowEvaluationCase(
                requireNonBlank(workflowCase.id(), "Workflow evaluation case id must not be blank"),
                requireNonBlank(workflowCase.prompt(), "Workflow evaluation case prompt must not be blank"),
                requireNonBlank(workflowCase.expectedBehavior(),
                        "Workflow evaluation case expected behavior must not be blank"));
    }

    private void ensureUniqueIds(List<String> ids, String type) {
        Set<String> seen = new LinkedHashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                throw new IllegalStateException("Duplicate workflow " + type + " id: " + id);
            }
        }
    }

    private String requireNonBlank(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalStateException(message);
        }
        return normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
