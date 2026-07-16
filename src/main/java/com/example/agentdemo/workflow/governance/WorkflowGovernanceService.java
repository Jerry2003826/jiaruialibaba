package com.example.agentdemo.workflow.governance;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.SecretRedactor;
import com.example.agentdemo.tool.ToolDescriptor;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.workflow.WorkflowCompiler;
import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowEdge;
import com.example.agentdemo.workflow.WorkflowNode;
import com.example.agentdemo.workflow.WorkflowNodeSchemaRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.net.URI;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class WorkflowGovernanceService {

    private static final Pattern PARSED_REFERENCE = Pattern.compile(
            "\\{\\{\\s*nodes\\.([a-zA-Z0-9_-]+)\\.parsed\\.([a-zA-Z0-9_.-]+)\\s*}}");
    private static final Pattern LAST_OUTPUT_PARSED_REFERENCE = Pattern.compile(
            "\\{\\{\\s*lastOutput\\.parsed\\.([a-zA-Z0-9_.-]+)\\s*}}");
    private static final Pattern STATE_REFERENCE = Pattern.compile(
            "\\{\\{\\s*state\\.([a-zA-Z0-9_-]+)(?:\\.[a-zA-Z0-9_.-]+)?\\s*}}");
    private static final Pattern JSON_PROMPT_FIELD = Pattern.compile(
            "[\\\"']([a-zA-Z][a-zA-Z0-9_]*)[\\\"']\\s*:");
    private static final Pattern HTTP_NODE_REFERENCE = Pattern.compile(
            "\\{\\{\\s*nodes\\.([a-zA-Z0-9_-]+)\\.([a-zA-Z0-9_.-]+)\\s*}}");
    private static final Pattern REPORT_SOURCE_REFERENCE = Pattern.compile(
            "^\\s*\\{\\{\\s*nodes\\.([a-zA-Z0-9_-]+)\\.([a-zA-Z0-9_.-]+)\\s*}}\\s*$");
    private static final Set<String> HTTP_OUTPUT_FIELDS = Set.of(
            "statusCode", "headers", "body", "json", "durationMs", "succeeded");
    private static final List<String> SUCCESS_CLAIMS = List.of(
            "退款已成功", "已退款", "物流查询成功", "订单查询成功", "查询成功", "已查询",
            "操作成功", "工单已创建", "已创建工单", "已通知", "已经发货", "已发货", "successfully");

    private final WorkflowRuleCatalog workflowRuleCatalog;
    private final WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry;
    private final ToolGatewayService toolGatewayService;
    private final WorkflowCompiler workflowCompiler;

    public WorkflowGovernanceService(WorkflowRuleCatalog workflowRuleCatalog,
            WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry,
            ToolGatewayService toolGatewayService,
            WorkflowCompiler workflowCompiler) {
        this.workflowRuleCatalog = workflowRuleCatalog;
        this.workflowNodeSchemaRegistry = workflowNodeSchemaRegistry;
        this.toolGatewayService = toolGatewayService;
        this.workflowCompiler = workflowCompiler;
    }

    public WorkflowGovernanceReport evaluateStatic(WorkflowDefinition definition, WorkflowBuilderContext context) {
        Objects.requireNonNull(definition, "definition");
        Map<String, WorkflowGovernanceRule> activeRules = activeRules(definition, context);

        try {
            workflowCompiler.compile(definition);
        }
        catch (BusinessException exception) {
            if ("WORKFLOW_HTTP_INLINE_SECRET_BLOCKED".equals(exception.getCode())) {
                return new WorkflowGovernanceReport(List.of(finding(
                        activeRules, "core-http-inline-secret", exception.getMessage(), List.of(),
                        Map.of("compilerCode", exception.getCode()))));
            }
            if (exception.getMessage() != null && exception.getMessage().contains("Variable aggregator")) {
                return new WorkflowGovernanceReport(List.of(finding(
                        activeRules, "core-variable-aggregator-candidates", exception.getMessage(), List.of(),
                        Map.of("compilerCode", exception.getCode()))));
            }
            List<WorkflowNode> reportNodes = definition.nodes().stream()
                    .filter(node -> "report_export".equalsIgnoreCase(node.type()))
                    .toList();
            if (!reportNodes.isEmpty() && exception.getMessage() != null
                    && exception.getMessage().contains("content")) {
                return new WorkflowGovernanceReport(List.of(finding(
                        activeRules, "core-report-source-contract", exception.getMessage(),
                        reportNodes.stream().map(WorkflowNode::id).toList(),
                        Map.of("compilerCode", exception.getCode()))));
            }
            if (!reportNodes.isEmpty() && exception.getMessage() != null
                    && exception.getMessage().contains("formats")) {
                return new WorkflowGovernanceReport(List.of(finding(
                        activeRules, "core-report-export-config", exception.getMessage(),
                        reportNodes.stream().map(WorkflowNode::id).toList(),
                        Map.of("compilerCode", exception.getCode()))));
            }
            if (!"WORKFLOW_UNSUPPORTED".equals(exception.getCode())
                    || exception.getMessage() == null
                    || !exception.getMessage().startsWith("Unsupported node type:")) {
                throw exception;
            }
            List<WorkflowNode> unsupportedNodes = unsupportedNodes(definition);
            if (unsupportedNodes.isEmpty()) {
                throw exception;
            }
            WorkflowGovernanceFinding finding = finding(
                    activeRules,
                    "core-registered-node-types",
                    "WorkflowCompiler rejected unregistered node types.",
                    unsupportedNodes.stream().map(WorkflowNode::id).toList(),
                    Map.of(
                            "compilerCode", exception.getCode(),
                            "compilerMessage", exception.getMessage(),
                            "unsupportedTypes", unsupportedNodes.stream()
                                    .collect(Collectors.toMap(WorkflowNode::id, node -> String.valueOf(node.type()),
                                            (left, right) -> left, LinkedHashMap::new))));
            return new WorkflowGovernanceReport(List.of(finding));
        }

        List<WorkflowGovernanceFinding> findings = new ArrayList<>();
        detectUnregisteredTools(definition, activeRules, findings);
        detectCapabilityMisrepresentation(definition, activeRules, findings);
        detectRuntimeBudgetFit(definition, activeRules, findings);
        detectHttpRequestRisks(definition, activeRules, findings);
        detectReportExportRisks(definition, activeRules, findings);
        detectUngroundedAuthoritativeData(definition, activeRules, findings);
        detectOutputContractInconsistency(definition, activeRules, findings);
        detectRawCustomerOutput(definition, activeRules, findings);
        detectUnsupportedClaims(definition, activeRules, findings);
        detectCompoundIssueLoss(definition, context, activeRules, findings);
        detectLowConfidenceClarification(definition, activeRules, findings);
        detectMissingOrderClarification(definition, context, activeRules, findings);
        detectToolFailureFallback(definition, activeRules, findings);
        return new WorkflowGovernanceReport(findings);
    }

    private void detectHttpRequestRisks(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        List<WorkflowNode> httpNodes = definition.nodes().stream()
                .filter(node -> "http_request".equalsIgnoreCase(node.type()))
                .toList();
        if (httpNodes.isEmpty()) {
            return;
        }
        Set<String> httpNodeIds = httpNodes.stream().map(WorkflowNode::id).collect(Collectors.toSet());
        List<String> placeholderNodes = httpNodes.stream()
                .filter(this::hasPlaceholderHttpUrl)
                .map(WorkflowNode::id)
                .toList();
        if (!placeholderNodes.isEmpty()) {
            addFinding(findings, activeRules, "core-http-placeholder-url",
                    "HTTP request nodes contain placeholder or local endpoint URLs.", placeholderNodes,
                    Map.of("placeholderNodes", placeholderNodes));
        }

        List<String> inlineSecretNodes = httpNodes.stream()
                .filter(node -> containsSensitiveHttpConfig(node.config()))
                .map(WorkflowNode::id)
                .toList();
        if (!inlineSecretNodes.isEmpty()) {
            addFinding(findings, activeRules, "core-http-inline-secret",
                    "HTTP request config contains inline secret-shaped fields.", inlineSecretNodes,
                    Map.of("inlineSecretNodes", inlineSecretNodes));
        }

        Map<String, Set<String>> invalidFields = new LinkedHashMap<>();
        for (WorkflowNode consumer : definition.nodes()) {
            for (String value : stringValues(consumer.config())) {
                Matcher matcher = HTTP_NODE_REFERENCE.matcher(value);
                while (matcher.find()) {
                    String sourceId = matcher.group(1);
                    String rootField = matcher.group(2).split("\\.")[0];
                    if (httpNodeIds.contains(sourceId) && !HTTP_OUTPUT_FIELDS.contains(rootField)) {
                        invalidFields.computeIfAbsent(consumer.id(), ignored -> new LinkedHashSet<>())
                                .add(sourceId + "." + rootField);
                    }
                }
            }
        }
        if (!invalidFields.isEmpty()) {
            addFinding(findings, activeRules, "core-http-output-fields",
                    "Downstream nodes reference fields outside the fixed HTTP response contract.",
                    invalidFields.keySet(), Map.of("invalidFields", invalidFields));
        }

        List<String> unhandled = httpNodes.stream()
                .filter(node -> !hasHttpFailureConsumer(definition, node))
                .map(WorkflowNode::id)
                .toList();
        if (!unhandled.isEmpty()) {
            addFinding(findings, activeRules, "core-http-failure-handling",
                    "HTTP request outcomes are not checked through succeeded or statusCode.", unhandled,
                    Map.of("unhandledHttpNodes", unhandled));
        }
    }

    private boolean hasPlaceholderHttpUrl(WorkflowNode node) {
        String configured = String.valueOf(node.config().getOrDefault("url", "")).trim();
        if (configured.isBlank() || configured.contains("{{")) {
            return configured.isBlank();
        }
        try {
            String host = URI.create(configured).getHost();
            if (host == null) {
                return true;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.equals("localhost")
                    || normalized.equals("example.com")
                    || normalized.endsWith(".example.com")
                    || normalized.endsWith(".example.test")
                    || normalized.endsWith(".example");
        }
        catch (IllegalArgumentException exception) {
            return true;
        }
    }

    private boolean containsSensitiveHttpConfig(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                boolean managedAuthorizationContainer = "authorization".equalsIgnoreCase(key)
                        && entry.getValue() instanceof Map<?, ?>;
                if (SecretRedactor.isSensitiveKey(key) && !"credentialId".equalsIgnoreCase(key)
                        && !managedAuthorizationContainer) {
                    return true;
                }
                if ("key".equalsIgnoreCase(key) && entry.getValue() != null
                        && SecretRedactor.isSensitiveKey(String.valueOf(entry.getValue()))) {
                    return true;
                }
                if (containsSensitiveHttpConfig(entry.getValue())) {
                    return true;
                }
            }
        }
        else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsSensitiveHttpConfig(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasHttpFailureConsumer(WorkflowDefinition definition, WorkflowNode httpNode) {
        Set<String> directlyDownstream = definition.edges().stream()
                .filter(edge -> edge.from().equals(httpNode.id()))
                .map(WorkflowEdge::to)
                .collect(Collectors.toSet());
        for (WorkflowNode candidate : reachableNodes(definition, httpNode.id())) {
            if (!"condition".equalsIgnoreCase(candidate.type())) {
                continue;
            }
            String text = String.join(" ", stringValues(candidate.config()));
            if (text.contains("{{nodes." + httpNode.id() + ".succeeded}}")
                    || text.contains("{{nodes." + httpNode.id() + ".statusCode}}")) {
                return true;
            }
            if (directlyDownstream.contains(candidate.id())
                    && (text.contains("{{lastOutput.succeeded}}") || text.contains("{{lastOutput.statusCode}}"))) {
                return true;
            }
        }
        return false;
    }

    private void detectReportExportRisks(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        List<WorkflowNode> reportNodes = definition.nodes().stream()
                .filter(node -> "report_export".equalsIgnoreCase(node.type()))
                .toList();
        if (reportNodes.isEmpty()) {
            return;
        }
        List<String> dangerousNames = reportNodes.stream()
                .filter(node -> dangerousReportFileName(String.valueOf(
                        node.config().getOrDefault("fileName", "report"))))
                .map(WorkflowNode::id)
                .toList();
        if (!dangerousNames.isEmpty()) {
            addFinding(findings, activeRules, "core-report-file-name",
                    "Report export file names contain path traversal, control characters, or dangerous extensions.",
                    dangerousNames, Map.of("dangerousFileNameNodes", dangerousNames));
        }

        Map<String, String> unsafeSources = new LinkedHashMap<>();
        Map<String, WorkflowNode> nodesById = definition.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::id, Function.identity()));
        for (WorkflowNode report : reportNodes) {
            Matcher sourceReference = REPORT_SOURCE_REFERENCE.matcher(
                    String.valueOf(report.config().getOrDefault("content", "")));
            if (!sourceReference.matches()) {
                continue;
            }
            WorkflowNode source = nodesById.get(sourceReference.group(1));
            if (source == null) {
                continue;
            }
            String prompt = String.join(" ", stringValues(source.config())).toLowerCase(Locale.ROOT);
            if (containsAny(prompt, "<script", "javascript:", "onerror=", "onclick=", "自定义css",
                    "custom css", "远程图片", "remote image", "data:image/")) {
                unsafeSources.put(report.id(), source.id());
            }
        }
        if (!unsafeSources.isEmpty()) {
            addFinding(findings, activeRules, "core-report-content-safety",
                    "Report source prompts request unsafe HTML, remote resources, or custom styling.",
                    unsafeSources.keySet(), Map.of("unsafeSourceNodes", unsafeSources));
        }
    }

    private boolean dangerousReportFileName(String value) {
        String fileName = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (fileName.isBlank() || fileName.contains("{{")) {
            return false;
        }
        return fileName.contains("..")
                || fileName.contains("/")
                || fileName.contains("\\")
                || fileName.chars().anyMatch(character -> character < 32 || character == 127)
                || fileName.matches(".*\\.(exe|com|bat|cmd|sh|ps1|js|jar|msi|app|dmg|pkg)$");
    }

    private Map<String, WorkflowGovernanceRule> activeRules(WorkflowDefinition definition,
            WorkflowBuilderContext context) {
        Map<String, WorkflowGovernanceRule> rules = new LinkedHashMap<>();
        if (context != null) {
            addPacks(rules, context.activeRulePacks());
            addPacks(rules, workflowRuleCatalog.activePacks(context.domain()));
        }
        else {
            addPacks(rules, workflowRuleCatalog.activePacks(null));
        }
        String graphDomain = workflowRuleCatalog.detectDomain(definition);
        if (graphDomain != null) {
            addPacks(rules, workflowRuleCatalog.activePacks(graphDomain));
        }
        return rules;
    }

    private void addPacks(Map<String, WorkflowGovernanceRule> rules, List<WorkflowRulePack> packs) {
        if (packs == null) {
            return;
        }
        packs.stream()
                .flatMap(pack -> pack.rules().stream())
                .forEach(rule -> rules.putIfAbsent(rule.id(), rule));
    }

    private List<WorkflowNode> unsupportedNodes(WorkflowDefinition definition) {
        Set<String> registeredTypes = workflowNodeSchemaRegistry.listSchemas().stream()
                .map(schema -> schema.type().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return definition.nodes().stream()
                .filter(node -> node.type() == null
                        || !registeredTypes.contains(node.type().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private void detectUnregisteredTools(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        Set<String> executableTools = toolGatewayService.listExecutableTools().stream()
                .map(ToolDescriptor::name)
                .collect(Collectors.toSet());
        Map<String, List<String>> unknownByNode = new LinkedHashMap<>();
        Set<String> missingToolNameNodes = new LinkedHashSet<>();
        for (WorkflowNode node : definition.nodes()) {
            if ("tool".equalsIgnoreCase(node.type())) {
                Object configuredName = node.config().get("toolName");
                if (configuredName == null || String.valueOf(configuredName).isBlank()) {
                    missingToolNameNodes.add(node.id());
                }
            }
            List<String> requestedTools = requestedTools(node);
            List<String> unknown = requestedTools.stream()
                    .filter(name -> !executableTools.contains(name))
                    .distinct()
                    .sorted()
                    .toList();
            if (!unknown.isEmpty()) {
                unknownByNode.put(node.id(), unknown);
            }
        }
        if (!unknownByNode.isEmpty() || !missingToolNameNodes.isEmpty()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("unknownTools", unknownByNode);
            evidence.put("missingToolNameNodes", missingToolNameNodes);
            Set<String> nodeIds = new LinkedHashSet<>(unknownByNode.keySet());
            nodeIds.addAll(missingToolNameNodes);
            addFinding(findings, activeRules, "core-registered-tools",
                    "Workflow tool nodes must name an executable tool from the current registry.",
                    nodeIds, evidence);
        }
    }

    private List<String> requestedTools(WorkflowNode node) {
        if ("tool".equalsIgnoreCase(node.type())) {
            Object toolName = node.config().get("toolName");
            return toolName == null || String.valueOf(toolName).isBlank()
                    ? List.of()
                    : List.of(String.valueOf(toolName));
        }
        if ("dynamic".equalsIgnoreCase(node.type())) {
            Object allowed = node.config().get("allowedTools");
            if (allowed instanceof Iterable<?> iterable) {
                List<String> names = new ArrayList<>();
                iterable.forEach(item -> names.add(String.valueOf(item)));
                return names;
            }
        }
        return List.of();
    }

    private void detectCapabilityMisrepresentation(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        List<WorkflowNode> currentTimeNodes = definition.nodes().stream()
                .filter(node -> "getCurrentTime".equals(node.config().get("toolName")))
                .toList();
        if (currentTimeNodes.isEmpty()) {
            return;
        }
        List<WorkflowNode> crmMisuse = currentTimeNodes.stream()
                .filter(node -> containsAny(currentTimeUsageText(definition, node),
                        "crm", "vip", "客户等级", "会员等级"))
                .toList();
        List<WorkflowNode> logisticsMisuse = currentTimeNodes.stream()
                .filter(node -> containsAny(currentTimeUsageText(definition, node),
                        "物流", "订单", "配送", "发货", "logistics", "shipment", "order status"))
                .toList();
        if (!crmMisuse.isEmpty()) {
            addFinding(findings, activeRules, "cs-authoritative-crm-vip-data",
                    "getCurrentTime is represented as CRM or VIP data.",
                    crmMisuse.stream().map(WorkflowNode::id).toList(),
                    currentTimeEvidence(definition, crmMisuse));
        }
        if (!logisticsMisuse.isEmpty()) {
            addFinding(findings, activeRules, "cs-real-order-and-logistics-lookup",
                    "getCurrentTime is represented as an order or logistics lookup.",
                    logisticsMisuse.stream().map(WorkflowNode::id).toList(),
                    currentTimeEvidence(definition, logisticsMisuse));
        }
    }

    private void detectRuntimeBudgetFit(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        List<String> longestLlmPath = longestLlmPath(definition);
        if (longestLlmPath.size() <= 2) {
            return;
        }
        addFinding(findings, activeRules, "core-runtime-budget-fit",
                "A workflow path contains too many serial LLM calls for the automated case deadline.",
                longestLlmPath,
                Map.of(
                        "maximumSerialLlmCalls", longestLlmPath.size(),
                        "allowedSerialLlmCalls", 2,
                        "llmNodePath", longestLlmPath));
    }

    private List<String> longestLlmPath(WorkflowDefinition definition) {
        Map<String, WorkflowNode> nodesById = definition.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::id, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, List<String>> outgoing = definition.edges().stream()
                .collect(Collectors.groupingBy(WorkflowEdge::from,
                        LinkedHashMap::new,
                        Collectors.mapping(WorkflowEdge::to, Collectors.toList())));
        List<WorkflowNode> starts = definition.nodes().stream()
                .filter(node -> "start".equalsIgnoreCase(node.type()))
                .toList();
        List<String> longest = List.of();
        for (WorkflowNode start : starts) {
            List<String> candidate = longestLlmPath(start.id(), nodesById, outgoing,
                    new LinkedHashSet<>(), List.of());
            if (candidate.size() > longest.size()) {
                longest = candidate;
            }
        }
        return longest;
    }

    private List<String> longestLlmPath(String nodeId,
            Map<String, WorkflowNode> nodesById,
            Map<String, List<String>> outgoing,
            Set<String> visited,
            List<String> llmPath) {
        if (!visited.add(nodeId)) {
            return llmPath;
        }
        WorkflowNode node = nodesById.get(nodeId);
        List<String> currentPath = new ArrayList<>(llmPath);
        if (node != null && "llm".equalsIgnoreCase(node.type())) {
            currentPath.add(node.id());
        }
        List<String> longest = List.copyOf(currentPath);
        for (String nextId : outgoing.getOrDefault(nodeId, List.of())) {
            List<String> candidate = longestLlmPath(nextId, nodesById, outgoing,
                    new LinkedHashSet<>(visited), currentPath);
            if (candidate.size() > longest.size()) {
                longest = candidate;
            }
        }
        return longest;
    }

    private String currentTimeUsageText(WorkflowDefinition definition, WorkflowNode timeNode) {
        StringBuilder usage = new StringBuilder();
        Map<String, WorkflowNode> nodesById = definition.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::id, Function.identity()));
        definition.edges().stream()
                .filter(edge -> edge.from().equals(timeNode.id()))
                .map(edge -> nodesById.get(edge.to()))
                .filter(Objects::nonNull)
                .filter(node -> {
                    String text = flatten(node);
                    return text.contains("{{lastOutput}}")
                            || text.contains("{{toolResult}}")
                            || text.contains("{{nodes." + timeNode.id() + ".");
                })
                .forEach(node -> usage.append(' ').append(referenceClauses(node, timeNode.id())));
        return usage.toString();
    }

    private String referenceClauses(WorkflowNode node, String sourceNodeId) {
        List<String> markers = List.of(
                "{{lastOutput}}",
                "{{toolResult}}",
                "{{nodes." + sourceNodeId + ".");
        StringBuilder clauses = new StringBuilder();
        for (String value : stringValues(node.config())) {
            for (String marker : markers) {
                int offset = value.indexOf(marker);
                while (offset >= 0) {
                    int start = previousClauseBoundary(value, offset);
                    int end = nextClauseBoundary(value, offset + marker.length());
                    clauses.append(' ').append(value, start, end).append(' ');
                    offset = value.indexOf(marker, offset + marker.length());
                }
            }
        }
        return clauses.toString().toLowerCase(Locale.ROOT);
    }

    private int previousClauseBoundary(String value, int offset) {
        for (int index = offset - 1; index >= 0; index--) {
            if (isClauseBoundary(value.charAt(index))) {
                return index + 1;
            }
        }
        return 0;
    }

    private int nextClauseBoundary(String value, int offset) {
        for (int index = offset; index < value.length(); index++) {
            if (isClauseBoundary(value.charAt(index))) {
                return index;
            }
        }
        return value.length();
    }

    private boolean isClauseBoundary(char value) {
        return value == '.' || value == '。' || value == ';' || value == '；'
                || value == '!' || value == '！' || value == '?' || value == '？' || value == '\n';
    }

    private Map<String, Object> currentTimeEvidence(WorkflowDefinition definition, List<WorkflowNode> nodes) {
        return Map.of(
                "toolName", "getCurrentTime",
                "nodes", nodes.stream().collect(Collectors.toMap(
                        WorkflowNode::id,
                        node -> currentTimeUsageText(definition, node),
                        (left, right) -> left,
                        LinkedHashMap::new)));
    }

    private void detectUngroundedAuthoritativeData(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        List<WorkflowNode> inventedLogistics = new ArrayList<>();
        List<WorkflowNode> inventedCrm = new ArrayList<>();
        for (WorkflowNode node : definition.nodes()) {
            if (!"llm".equalsIgnoreCase(node.type())) {
                continue;
            }
            String text = flatten(node).toLowerCase(Locale.ROOT);
            if (containsAny(text, "包裹正在", "配送中", "预计明天到达", "物流状态为", "订单状态为",
                    "package is", "order status is", "shipment is")
                    && !hasGroundedLookup(definition, node, "order")) {
                inventedLogistics.add(node);
            }
            if (containsAny(text, "您是 vip", "vip 客户", "会员等级为", "客户等级为", "you are vip")
                    && !hasGroundedLookup(definition, node, "crm")) {
                inventedCrm.add(node);
            }
        }
        if (!inventedLogistics.isEmpty()) {
            addFinding(findings, activeRules, "cs-real-order-and-logistics-lookup",
                    "The workflow states a definitive order or logistics status without grounded lookup output.",
                    inventedLogistics.stream().map(WorkflowNode::id).toList(),
                    Map.of("ungroundedStatements", inventedLogistics.stream()
                            .collect(Collectors.toMap(WorkflowNode::id, this::flatten,
                                    (left, right) -> left, LinkedHashMap::new))));
        }
        if (!inventedCrm.isEmpty()) {
            addFinding(findings, activeRules, "cs-authoritative-crm-vip-data",
                    "The workflow states a definitive CRM or VIP status without grounded lookup output.",
                    inventedCrm.stream().map(WorkflowNode::id).toList(),
                    Map.of("ungroundedStatements", inventedCrm.stream()
                            .collect(Collectors.toMap(WorkflowNode::id, this::flatten,
                                    (left, right) -> left, LinkedHashMap::new))));
        }
    }

    private boolean hasGroundedLookup(WorkflowDefinition definition, WorkflowNode consumer, String capability) {
        return ancestorTools(definition, consumer.id()).stream()
                .filter(tool -> {
                    String name = String.valueOf(tool.config().getOrDefault("toolName", ""))
                            .toLowerCase(Locale.ROOT);
                    return "order".equals(capability)
                            ? containsAny(name, "order", "logistic", "shipping", "tracking")
                            : containsAny(name, "crm", "customer", "vip", "member");
                })
                .anyMatch(tool -> dominates(definition, tool.id(), consumer.id())
                        && claimConsumesToolResult(definition, tool, consumer));
    }

    private void detectOutputContractInconsistency(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        Map<String, WorkflowNode> nodesById = definition.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::id, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        List<Map<String, Object>> issues = new ArrayList<>();
        Set<String> nodeIds = new LinkedHashSet<>();

        for (WorkflowNode consumer : definition.nodes()) {
            for (Map.Entry<String, Object> configEntry : consumer.config().entrySet()) {
                boolean ownWriteStateValue = "writeState".equals(configEntry.getKey());
                for (String text : stringValues(configEntry.getValue())) {
                    Matcher matcher = PARSED_REFERENCE.matcher(text);
                    while (matcher.find()) {
                        String sourceNodeId = matcher.group(1);
                        String fieldPath = matcher.group(2);
                        WorkflowNode source = nodesById.get(sourceNodeId);
                        if (source == null || !dominates(definition, sourceNodeId, consumer.id())
                                || !hasStructuredField(source, fieldPath)) {
                            Map<String, Object> issue = new LinkedHashMap<>();
                            issue.put("kind", "missing-upstream-structured-field");
                            issue.put("consumerNodeId", consumer.id());
                            issue.put("sourceNodeId", sourceNodeId);
                            issue.put("field", fieldPath);
                            issues.add(issue);
                            nodeIds.add(consumer.id());
                            nodeIds.add(sourceNodeId);
                        }
                    }
                    Matcher lastOutputMatcher = LAST_OUTPUT_PARSED_REFERENCE.matcher(text);
                    while (lastOutputMatcher.find()) {
                        String fieldPath = lastOutputMatcher.group(1);
                        List<WorkflowNode> predecessors = ownWriteStateValue
                                ? List.of()
                                : directPredecessors(definition, consumer.id());
                        WorkflowNode source = ownWriteStateValue
                                ? consumer
                                : (predecessors.size() == 1 ? predecessors.getFirst() : null);
                        if (source == null || !hasStructuredField(source, fieldPath)) {
                            Map<String, Object> issue = new LinkedHashMap<>();
                            issue.put("kind", ownWriteStateValue
                                    ? "missing-write-state-source-field"
                                    : "missing-last-output-structured-field");
                            issue.put("consumerNodeId", consumer.id());
                            issue.put("sourceNodeId", source == null ? "ambiguous" : source.id());
                            issue.put("reference", "lastOutput.parsed." + fieldPath);
                            issue.put("field", fieldPath);
                            issues.add(issue);
                            nodeIds.add(consumer.id());
                            if (source != null) {
                                nodeIds.add(source.id());
                            }
                        }
                    }
                    Matcher stateMatcher = STATE_REFERENCE.matcher(text);
                    while (stateMatcher.find()) {
                        String stateKey = stateMatcher.group(1);
                        List<WorkflowNode> writers = definition.nodes().stream()
                                .filter(writer -> writesStateKey(writer, stateKey)
                                        && isReachable(definition, writer.id(), consumer.id()))
                                .toList();
                        boolean hasDominatingWriter = writers.stream()
                                .anyMatch(writer -> dominates(definition, writer.id(), consumer.id()));
                        if (!hasDominatingWriter) {
                            Map<String, Object> issue = new LinkedHashMap<>();
                            issue.put("kind", writers.isEmpty()
                                    ? "missing-upstream-state-write"
                                    : "state-writer-does-not-dominate-consumer");
                            issue.put("consumerNodeId", consumer.id());
                            issue.put("reference", "state." + stateKey);
                            issue.put("writerNodeIds", writers.stream().map(WorkflowNode::id).toList());
                            issues.add(issue);
                            nodeIds.add(consumer.id());
                            writers.forEach(writer -> nodeIds.add(writer.id()));
                        }
                    }
                }
            }
        }

        for (WorkflowNode node : definition.nodes()) {
            if (!"llm".equalsIgnoreCase(node.type())
                    || !"json".equalsIgnoreCase(String.valueOf(node.config().get("outputMode")))) {
                continue;
            }
            Map<String, Object> properties = schemaProperties(node.config().get("outputSchema"));
            Set<String> schemaFields = schemaFieldNames(node.config().get("outputSchema"));
            Set<String> promptFields = jsonPromptFields(String.valueOf(node.config().getOrDefault("prompt", "")));
            Set<String> undeclared = promptFields.stream()
                    .filter(field -> !schemaFields.contains(field))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!undeclared.isEmpty()) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("kind", "prompt-schema-field-conflict");
                issue.put("nodeId", node.id());
                issue.put("promptFields", promptFields);
                issue.put("schemaFields", schemaFields);
                issue.put("undeclaredPromptFields", undeclared);
                issues.add(issue);
                nodeIds.add(node.id());
            }
        }

        if (!issues.isEmpty()) {
            addFinding(findings, activeRules, "core-output-contract-consistency",
                    "Structured-output prompts, schemas, and downstream condition fields disagree.",
                    nodeIds, Map.of("issues", issues));
        }
    }

    private boolean hasStructuredField(WorkflowNode node, String fieldPath) {
        if (!"llm".equalsIgnoreCase(node.type())
                || !"json".equalsIgnoreCase(String.valueOf(node.config().get("outputMode")))) {
            return false;
        }
        Object current = node.config().get("outputSchema");
        for (String segment : fieldPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> schema)) {
                return false;
            }
            Object properties = schema.get("properties");
            if (!(properties instanceof Map<?, ?> propertyMap)) {
                return false;
            }
            current = propertyMap.get(segment);
            if (current == null) {
                return false;
            }
        }
        return true;
    }

    private boolean writesStateKey(WorkflowNode node, String stateKey) {
        Object writeState = node.config().get("writeState");
        return writeState instanceof Map<?, ?> stateMap && stateMap.containsKey(stateKey);
    }

    private boolean isReachable(WorkflowDefinition definition, String sourceId, String targetId) {
        return !sourceId.equals(targetId) && reachableNodes(definition, sourceId).stream()
                .anyMatch(node -> node.id().equals(targetId));
    }

    private List<WorkflowNode> directPredecessors(WorkflowDefinition definition, String nodeId) {
        Map<String, WorkflowNode> nodesById = definition.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::id, Function.identity(), (left, right) -> left,
                        LinkedHashMap::new));
        return definition.edges().stream()
                .filter(edge -> edge.to().equals(nodeId))
                .map(edge -> nodesById.get(edge.from()))
                .filter(Objects::nonNull)
                .toList();
    }

    private boolean dominates(WorkflowDefinition definition, String dominatorId, String targetId) {
        if (dominatorId == null || targetId == null) {
            return false;
        }
        if (dominatorId.equals(targetId)) {
            return true;
        }
        if (!isReachable(definition, dominatorId, targetId)) {
            return false;
        }
        String startId = definition.nodes().stream()
                .filter(node -> "start".equalsIgnoreCase(node.type()))
                .map(WorkflowNode::id)
                .findFirst()
                .orElse(null);
        if (startId == null) {
            return false;
        }
        if (startId.equals(dominatorId)) {
            return true;
        }
        return !isReachableAvoiding(definition, startId, targetId, dominatorId);
    }

    private boolean isReachableAvoiding(WorkflowDefinition definition, String sourceId, String targetId,
            String excludedNodeId) {
        if (sourceId.equals(excludedNodeId)) {
            return false;
        }
        Map<String, List<String>> outgoing = definition.edges().stream()
                .collect(Collectors.groupingBy(WorkflowEdge::from,
                        LinkedHashMap::new,
                        Collectors.mapping(WorkflowEdge::to, Collectors.toList())));
        Deque<String> pending = new ArrayDeque<>();
        pending.add(sourceId);
        Set<String> visited = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            String currentId = pending.removeFirst();
            if (!visited.add(currentId) || currentId.equals(excludedNodeId)) {
                continue;
            }
            if (currentId.equals(targetId)) {
                return true;
            }
            outgoing.getOrDefault(currentId, List.of()).stream()
                    .filter(next -> !next.equals(excludedNodeId))
                    .forEach(pending::addLast);
        }
        return false;
    }

    private Set<String> jsonPromptFields(String prompt) {
        Set<String> fields = new LinkedHashSet<>();
        Matcher matcher = JSON_PROMPT_FIELD.matcher(prompt);
        while (matcher.find()) {
            fields.add(matcher.group(1));
        }
        return fields;
    }

    private Set<String> schemaFieldNames(Object schemaValue) {
        Set<String> fields = new LinkedHashSet<>();
        collectSchemaFieldNames(schemaValue, fields);
        return fields;
    }

    private void collectSchemaFieldNames(Object schemaValue, Set<String> fields) {
        if (!(schemaValue instanceof Map<?, ?> schema)) {
            return;
        }
        Object properties = schema.get("properties");
        if (properties instanceof Map<?, ?> propertyMap) {
            for (Map.Entry<?, ?> entry : propertyMap.entrySet()) {
                fields.add(String.valueOf(entry.getKey()));
                collectSchemaFieldNames(entry.getValue(), fields);
            }
        }
        collectSchemaFieldNames(schema.get("items"), fields);
        for (String composition : List.of("allOf", "anyOf", "oneOf")) {
            Object alternatives = schema.get(composition);
            if (alternatives instanceof Iterable<?> iterable) {
                iterable.forEach(alternative -> collectSchemaFieldNames(alternative, fields));
            }
        }
    }

    private void detectRawCustomerOutput(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        Set<String> terminalNodeIds = definition.edges().stream()
                .filter(edge -> definition.nodes().stream()
                        .anyMatch(node -> node.id().equals(edge.to()) && "end".equalsIgnoreCase(node.type())))
                .map(WorkflowEdge::from)
                .collect(Collectors.toSet());
        List<WorkflowNode> rawTerminalNodes = definition.nodes().stream()
                .filter(node -> terminalNodeIds.contains(node.id()))
                .filter(node -> "llm".equalsIgnoreCase(node.type()))
                .filter(node -> {
                    String prompt = String.valueOf(node.config().getOrDefault("prompt", ""))
                            .toLowerCase(Locale.ROOT);
                    return "json".equalsIgnoreCase(String.valueOf(node.config().get("outputMode")))
                            || (prompt.contains("只输出") && prompt.contains("json"))
                            || prompt.contains("return only json");
                })
                .toList();
        if (!rawTerminalNodes.isEmpty()) {
            addFinding(findings, activeRules, "core-customer-readable-final-output",
                    "A customer-facing terminal LLM is configured to return raw JSON.",
                    rawTerminalNodes.stream().map(WorkflowNode::id).toList(),
                    Map.of("terminalOutputModes", rawTerminalNodes.stream().collect(Collectors.toMap(
                            WorkflowNode::id,
                            node -> String.valueOf(node.config().getOrDefault("outputMode", "text")),
                            (left, right) -> left,
                            LinkedHashMap::new))));
        }
    }

    private void detectUnsupportedClaims(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        Map<String, List<String>> claimsByNode = new LinkedHashMap<>();
        Map<String, Map<String, Object>> unsupportedCapabilitiesByNode = new LinkedHashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            if (!"llm".equalsIgnoreCase(node.type())) {
                continue;
            }
            String text = flatten(node).toLowerCase(Locale.ROOT);
            List<String> claims = SUCCESS_CLAIMS.stream()
                    .filter(claim -> text.contains(claim.toLowerCase(Locale.ROOT)))
                    .toList();
            List<String> unsupportedClaims = claims.stream()
                    .filter(claim -> !isClaimAuthorized(definition, node, claim))
                    .toList();
            if (!unsupportedClaims.isEmpty()) {
                claimsByNode.put(node.id(), unsupportedClaims);
            }
            List<String> simulatedSearchSignals = List.of(
                            "模拟搜索", "模拟检索", "simulate search", "simulated search", "pretend to search")
                    .stream()
                    .filter(text::contains)
                    .toList();
            boolean admitsMissingExternalSearch = containsAny(text,
                    "未接入外部web search", "未接入外部 web search", "没有外部web search",
                    "without external web search", "no external web search");
            if ((!simulatedSearchSignals.isEmpty() || admitsMissingExternalSearch)
                    && !hasGroundedExternalSearch(definition, node)) {
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("kind", "simulated-external-search");
                evidence.put("signals", simulatedSearchSignals.isEmpty()
                        ? List.of("missing-external-search-admission")
                        : simulatedSearchSignals);
                evidence.put("prompt", String.valueOf(node.config().getOrDefault("prompt", "")));
                unsupportedCapabilitiesByNode.put(node.id(), evidence);
            }
        }
        if (!claimsByNode.isEmpty() || !unsupportedCapabilitiesByNode.isEmpty()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("unsupportedClaims", claimsByNode);
            evidence.put("unsupportedCapabilities", unsupportedCapabilitiesByNode);
            Set<String> nodeIds = new LinkedHashSet<>(claimsByNode.keySet());
            nodeIds.addAll(unsupportedCapabilitiesByNode.keySet());
            addFinding(findings, activeRules, "core-unsupported-business-claims",
                    "LLM prompts claim unsupported business actions or external capabilities without authoritative tool evidence.",
                    nodeIds, evidence);
        }
    }

    private boolean hasGroundedExternalSearch(WorkflowDefinition definition, WorkflowNode consumer) {
        boolean dedicatedTavilyNode = definition.nodes().stream()
                .filter(node -> "tavily_search".equalsIgnoreCase(node.type()))
                .anyMatch(node -> dominates(definition, node.id(), consumer.id())
                        && claimConsumesToolResult(definition, node, consumer));
        if (dedicatedTavilyNode) {
            return true;
        }
        return ancestorTools(definition, consumer.id()).stream()
                .filter(tool -> containsAny(
                        String.valueOf(tool.config().getOrDefault("toolName", "")).toLowerCase(Locale.ROOT),
                        "tavily", "websearch", "web_search", "searchweb", "browse", "browser"))
                .anyMatch(tool -> dominates(definition, tool.id(), consumer.id())
                        && claimConsumesToolResult(definition, tool, consumer));
    }

    private boolean isClaimAuthorized(WorkflowDefinition definition, WorkflowNode claimNode, String claim) {
        return ancestorTools(definition, claimNode.id()).stream()
                .anyMatch(tool -> toolSupportsClaim(tool, claim)
                        && dominates(definition, tool.id(), claimNode.id())
                        && claimConsumesToolResult(definition, tool, claimNode));
    }

    private boolean toolSupportsClaim(WorkflowNode tool, String claim) {
        String toolName = String.valueOf(tool.config().getOrDefault("toolName", "")).toLowerCase(Locale.ROOT);
        String normalizedClaim = claim.toLowerCase(Locale.ROOT);
        if (containsAny(normalizedClaim, "退款", "refund")) {
            return containsAny(toolName, "refund", "payment", "reimburse");
        }
        if (containsAny(normalizedClaim, "工单", "ticket")) {
            return containsAny(toolName, "ticket", "workorder", "case");
        }
        if (containsAny(normalizedClaim, "通知", "notified")) {
            return containsAny(toolName, "notify", "message", "email", "sms");
        }
        if (containsAny(normalizedClaim, "发货", "shipped")) {
            return containsAny(toolName, "ship", "fulfill", "dispatch");
        }
        if (containsAny(normalizedClaim, "物流", "订单", "logistics", "shipment", "order")) {
            return containsAny(toolName, "order", "logistic", "shipping", "tracking");
        }
        if (containsAny(normalizedClaim, "crm", "vip", "会员", "客户等级")) {
            return containsAny(toolName, "crm", "customer", "vip", "member");
        }
        if (containsAny(normalizedClaim, "查询", "queried", "lookup")) {
            return containsAny(toolName, "query", "lookup", "search", "order", "crm", "logistic");
        }
        return !toolName.isBlank()
                && !containsAny(toolName, "getcurrenttime", "calculate", "query", "lookup", "search");
    }

    private boolean claimConsumesToolResult(WorkflowDefinition definition, WorkflowNode tool, WorkflowNode claimNode) {
        return nodeConsumesToolResult(definition, tool, claimNode)
                || claimUsesSuccessfulToolPredicate(definition, tool, claimNode);
    }

    private boolean nodeConsumesToolResult(WorkflowDefinition definition, WorkflowNode tool, WorkflowNode consumer) {
        String consumerText = flatten(consumer);
        if (consumerText.contains("{{nodes." + tool.id() + ".")) {
            return true;
        }
        boolean directlyPrecedes = definition.edges().stream()
                .anyMatch(edge -> edge.from().equals(tool.id()) && edge.to().equals(consumer.id()));
        return directlyPrecedes && (consumerText.contains("{{lastOutput")
                || consumerText.contains("{{toolResult"));
    }

    private boolean claimUsesSuccessfulToolPredicate(WorkflowDefinition definition, WorkflowNode tool,
            WorkflowNode claimNode) {
        return definition.nodes().stream()
                .filter(node -> "condition".equalsIgnoreCase(node.type()))
                .filter(condition -> dominates(definition, tool.id(), condition.id()))
                .filter(condition -> dominates(definition, condition.id(), claimNode.id()))
                .filter(condition -> nodeConsumesToolResult(definition, tool, condition))
                .anyMatch(condition -> {
                    String successEdge = successfulPredicateEdge(condition);
                    return successEdge != null
                            && reachesClaimOnlyFromBranch(definition, condition, claimNode, successEdge);
                });
    }

    private String successfulPredicateEdge(WorkflowNode condition) {
        String left = String.valueOf(condition.config().getOrDefault("left", "")).toLowerCase(Locale.ROOT);
        String operator = String.valueOf(condition.config().getOrDefault("operator", "equals"))
                .toLowerCase(Locale.ROOT);
        Object right = condition.config().get("right");
        boolean successField = containsAny(left, "found", "succeeded", "success", "status");
        if (!successField) {
            return null;
        }
        String normalizedRight = String.valueOf(right).toLowerCase(Locale.ROOT);
        boolean positiveValue = Boolean.TRUE.equals(right)
                || Set.of("true", "found", "success", "succeeded", "ok").contains(normalizedRight);
        if ("equals".equals(operator)) {
            if (positiveValue) {
                return "true";
            }
            if (Boolean.FALSE.equals(right) || "false".equals(normalizedRight)) {
                return "false";
            }
            return null;
        }
        if (Set.of("contains", "startswith", "endswith").contains(operator) && positiveValue) {
            return "true";
        }
        return null;
    }

    private boolean reachesClaimOnlyFromBranch(WorkflowDefinition definition, WorkflowNode condition,
            WorkflowNode claimNode, String successEdge) {
        List<WorkflowEdge> outgoing = definition.edges().stream()
                .filter(edge -> edge.from().equals(condition.id()))
                .toList();
        boolean successReaches = outgoing.stream()
                .filter(edge -> successEdge.equalsIgnoreCase(edge.condition()))
                .anyMatch(edge -> edge.to().equals(claimNode.id())
                        || isReachable(definition, edge.to(), claimNode.id()));
        boolean otherReaches = outgoing.stream()
                .filter(edge -> !successEdge.equalsIgnoreCase(edge.condition()))
                .anyMatch(edge -> edge.to().equals(claimNode.id())
                        || isReachable(definition, edge.to(), claimNode.id()));
        return successReaches && !otherReaches;
    }

    private List<WorkflowNode> ancestorTools(WorkflowDefinition definition, String nodeId) {
        Map<String, List<String>> predecessors = definition.edges().stream()
                .collect(Collectors.groupingBy(WorkflowEdge::to,
                        LinkedHashMap::new,
                        Collectors.mapping(WorkflowEdge::from, Collectors.toList())));
        Map<String, WorkflowNode> nodesById = definition.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::id, Function.identity()));
        Deque<String> pending = new ArrayDeque<>(predecessors.getOrDefault(nodeId, List.of()));
        Set<String> visited = new LinkedHashSet<>();
        List<WorkflowNode> tools = new ArrayList<>();
        while (!pending.isEmpty()) {
            String currentId = pending.removeFirst();
            if (!visited.add(currentId)) {
                continue;
            }
            WorkflowNode current = nodesById.get(currentId);
            if (current != null && "tool".equalsIgnoreCase(current.type())) {
                tools.add(current);
            }
            pending.addAll(predecessors.getOrDefault(currentId, List.of()));
        }
        return tools;
    }

    private void detectCompoundIssueLoss(WorkflowDefinition definition, WorkflowBuilderContext context,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        String lockedSpec = context == null ? "" : context.lockedSpec().toLowerCase(Locale.ROOT);
        if (!requiresCompoundIssues(lockedSpec)) {
            return;
        }
        Map<String, List<String>> singleLabelFields = new LinkedHashMap<>();
        for (WorkflowNode node : definition.nodes()) {
            if (!"llm".equalsIgnoreCase(node.type())) {
                continue;
            }
            Map<String, Object> properties = schemaProperties(node.config().get("outputSchema"));
            if (properties.isEmpty() || supportsMultipleIssues(properties) || !looksLikeClassifier(definition, node)) {
                continue;
            }
            List<String> fields = properties.entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof Map<?, ?> field
                            && "string".equals(String.valueOf(field.get("type")))
                            && field.get("enum") instanceof Collection<?>)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!fields.isEmpty()) {
                singleLabelFields.put(node.id(), fields);
            }
        }
        if (!singleLabelFields.isEmpty()) {
            addFinding(findings, activeRules, "cs-multi-issue-preservation",
                    "The locked spec requires compound issues, but the classifier emits one exclusive label.",
                    singleLabelFields.keySet(), Map.of(
                            "singleLabelFields", singleLabelFields,
                            "lockedSpec", context.lockedSpec()));
        }
    }

    private void detectLowConfidenceClarification(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        List<WorkflowNode> unsafeClassifiers = new ArrayList<>();
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (WorkflowNode classifier : definition.nodes()) {
            if (!"llm".equalsIgnoreCase(classifier.type()) || !looksLikeClassifier(definition, classifier)) {
                continue;
            }
            Map<String, Object> properties = schemaProperties(classifier.config().get("outputSchema"));
            if (properties.isEmpty()) {
                continue;
            }
            boolean hasConfidence = properties.containsKey("confidence");
            boolean hasUnknownSignal = hasUnknownSignal(properties);
            boolean lowConfidenceBranch = hasConfidence
                    && hasClarificationBranch(definition, classifier, "confidence");
            boolean unknownBranch = hasUnknownSignal
                    && hasClarificationBranch(definition, classifier, "unknown");
            if ((!hasConfidence && !hasUnknownSignal) || (!lowConfidenceBranch && !unknownBranch)) {
                unsafeClassifiers.add(classifier);
                Map<String, Object> classifierEvidence = new LinkedHashMap<>();
                classifierEvidence.put("missingUncertaintySignal", !hasConfidence && !hasUnknownSignal);
                classifierEvidence.put("hasConfidence", hasConfidence);
                classifierEvidence.put("hasUnknownSignal", hasUnknownSignal);
                classifierEvidence.put("lowConfidenceBranch", lowConfidenceBranch);
                classifierEvidence.put("unknownBranch", unknownBranch);
                evidence.put(classifier.id(), classifierEvidence);
            }
        }
        if (!unsafeClassifiers.isEmpty()) {
            addFinding(findings, activeRules, "cs-low-confidence-clarification",
                    "A confidence-producing classifier has no effective low-confidence clarification path.",
                    unsafeClassifiers.stream().map(WorkflowNode::id).toList(),
                    Map.of("classifiers", evidence));
        }
    }

    private boolean hasUnknownSignal(Map<String, Object> properties) {
        return properties.entrySet().stream().anyMatch(entry -> {
            String fieldName = entry.getKey().toLowerCase(Locale.ROOT);
            if (containsAny(fieldName, "unknown", "uncertain", "ambiguous", "needsclarification",
                    "needs_clarification")) {
                return true;
            }
            if (!(entry.getValue() instanceof Map<?, ?> field)
                    || !(field.get("enum") instanceof Iterable<?> values)) {
                return false;
            }
            for (Object value : values) {
                if (containsAny(String.valueOf(value).toLowerCase(Locale.ROOT),
                        "unknown", "uncertain", "ambiguous", "needs_clarification")) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean hasClarificationBranch(WorkflowDefinition definition, WorkflowNode classifier, String signal) {
        return definition.nodes().stream()
                .filter(node -> "condition".equalsIgnoreCase(node.type()))
                .filter(condition -> dominates(definition, classifier.id(), condition.id()))
                .filter(condition -> conditionConsumesClassifierSignal(definition, classifier, condition, signal))
                .anyMatch(condition -> clarificationEdgeTargets(definition, condition, signal).stream()
                        .anyMatch(target -> branchContainsClarification(definition, target)));
    }

    private boolean conditionConsumesClassifierSignal(WorkflowDefinition definition, WorkflowNode classifier,
            WorkflowNode condition, String signal) {
        String text = flatten(condition).toLowerCase(Locale.ROOT);
        if ("confidence".equals(signal)) {
            if (!text.contains("nodes." + classifier.id().toLowerCase(Locale.ROOT) + ".parsed.confidence")
                    && !stateSignalTracesToClassifier(definition, classifier, condition, signal)) {
                return false;
            }
            String operator = String.valueOf(condition.config().getOrDefault("operator", ""))
                    .toLowerCase(Locale.ROOT);
            return containsAny(operator, "lessthan", "less_than", "below", "greaterthan", "greater_than");
        }
        boolean directReference = text.contains("nodes." + classifier.id().toLowerCase(Locale.ROOT) + ".parsed.");
        return (directReference || stateSignalTracesToClassifier(definition, classifier, condition, signal))
                && containsAny(text, "unknown", "uncertain", "ambiguous", "needs_clarification");
    }

    private boolean stateSignalTracesToClassifier(WorkflowDefinition definition, WorkflowNode classifier,
            WorkflowNode condition, String signal) {
        Set<String> stateKeys = new LinkedHashSet<>();
        for (String value : stringValues(condition.config())) {
            Matcher matcher = STATE_REFERENCE.matcher(value);
            while (matcher.find()) {
                stateKeys.add(matcher.group(1));
            }
        }
        for (String stateKey : stateKeys) {
            for (WorkflowNode writer : definition.nodes()) {
                if (!writesStateKey(writer, stateKey)
                        || !dominates(definition, writer.id(), condition.id())
                        || (!writer.id().equals(classifier.id())
                                && !dominates(definition, classifier.id(), writer.id()))) {
                    continue;
                }
                Object writeState = writer.config().get("writeState");
                Object sourceValue = writeState instanceof Map<?, ?> stateMap ? stateMap.get(stateKey) : null;
                if (sourceValue instanceof String source
                        && stateWriteSourceMatchesClassifier(classifier, writer, source, signal)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean stateWriteSourceMatchesClassifier(WorkflowNode classifier, WorkflowNode writer,
            String source, String signal) {
        if (writer.id().equals(classifier.id())) {
            Matcher matcher = LAST_OUTPUT_PARSED_REFERENCE.matcher(source);
            return matcher.find() && structuredFieldProvidesSignal(classifier, matcher.group(1), signal);
        }
        Matcher matcher = PARSED_REFERENCE.matcher(source);
        return matcher.find()
                && classifier.id().equals(matcher.group(1))
                && structuredFieldProvidesSignal(classifier, matcher.group(2), signal);
    }

    private boolean structuredFieldProvidesSignal(WorkflowNode classifier, String fieldPath, String signal) {
        String topLevelField = fieldPath.split("\\.")[0];
        if ("confidence".equals(signal)) {
            return "confidence".equalsIgnoreCase(topLevelField);
        }
        Object field = schemaProperties(classifier.config().get("outputSchema")).get(topLevelField);
        if (!(field instanceof Map<?, ?> fieldSchema)) {
            return false;
        }
        if (containsAny(topLevelField.toLowerCase(Locale.ROOT),
                "unknown", "uncertain", "ambiguous", "needs_clarification")) {
            return true;
        }
        Object values = fieldSchema.get("enum");
        if (!(values instanceof Iterable<?> iterable)) {
            return false;
        }
        for (Object value : iterable) {
            if (containsAny(String.valueOf(value).toLowerCase(Locale.ROOT),
                    "unknown", "uncertain", "ambiguous", "needs_clarification")) {
                return true;
            }
        }
        return false;
    }

    private List<String> clarificationEdgeTargets(WorkflowDefinition definition, WorkflowNode condition,
            String signal) {
        String operator = String.valueOf(condition.config().getOrDefault("operator", ""))
                .toLowerCase(Locale.ROOT);
        boolean clarificationOnTrue = !"confidence".equals(signal) || containsAny(operator, "less", "below");
        String expected = clarificationOnTrue ? "true" : "false";
        return definition.edges().stream()
                .filter(edge -> edge.from().equals(condition.id())
                        && expected.equalsIgnoreCase(edge.condition()))
                .map(WorkflowEdge::to)
                .toList();
    }

    private boolean branchContainsClarification(WorkflowDefinition definition, String targetId) {
        List<WorkflowNode> branch = new ArrayList<>();
        definition.nodes().stream()
                .filter(node -> node.id().equals(targetId))
                .findFirst()
                .ifPresent(branch::add);
        branch.addAll(reachableNodes(definition, targetId));
        return branch.stream()
                .map(this::flatten)
                .map(text -> text.toLowerCase(Locale.ROOT))
                .anyMatch(text -> containsAny(text,
                        "低置信度", "不确定", "澄清", "请问", "请确认", "补充信息", "clarif",
                        "not certain", "more information"));
    }

    private boolean requiresCompoundIssues(String lockedSpec) {
        return containsAny(lockedSpec, "多个问题", "多问题", "复合问题", "同时反馈", "同时处理", "分别处理", "多标签")
                || ((lockedSpec.contains("破损") || lockedSpec.contains("damage"))
                        && (lockedSpec.contains("物流") || lockedSpec.contains("退款")
                                || lockedSpec.contains("shipping") || lockedSpec.contains("refund")));
    }

    private boolean supportsMultipleIssues(Map<String, Object> properties) {
        long booleanFields = properties.values().stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(field -> "boolean".equals(String.valueOf(field.get("type"))))
                .count();
        boolean hasArray = properties.values().stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .anyMatch(field -> "array".equals(String.valueOf(field.get("type"))));
        return hasArray || booleanFields >= 2;
    }

    private boolean looksLikeClassifier(WorkflowDefinition definition, WorkflowNode node) {
        String text = flatten(node).toLowerCase(Locale.ROOT);
        if (containsAny(text, "判断", "分类", "识别", "路由", "intent", "classif", "issue")) {
            return true;
        }
        String referencePrefix = "nodes." + node.id() + ".parsed.";
        return definition.nodes().stream()
                .flatMap(candidate -> stringValues(candidate.config()).stream())
                .anyMatch(value -> value.contains(referencePrefix));
    }

    private void detectMissingOrderClarification(WorkflowDefinition definition, WorkflowBuilderContext context,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        List<WorkflowNode> orderTools = orderLookupTools(definition);
        String lockedSpec = context == null ? "" : context.lockedSpec().toLowerCase(Locale.ROOT);
        if (orderTools.isEmpty() || !containsAny(lockedSpec + " " + flatten(definition).toLowerCase(Locale.ROOT),
                "订单", "order", "物流", "shipment")) {
            return;
        }
        List<WorkflowNode> missingClarification = orderTools.stream()
                .filter(tool -> !hasOrderIdentifierFallback(definition, tool))
                .toList();
        if (!missingClarification.isEmpty()) {
            addFinding(findings, activeRules, "cs-missing-order-clarification",
                    "Order-specific lookup has no branch that requests a missing order identifier.",
                    missingClarification.stream().map(WorkflowNode::id).toList(),
                    Map.of("requiredData", "orderId", "lookupTools", missingClarification.stream()
                            .collect(Collectors.toMap(WorkflowNode::id,
                                    node -> node.config().get("toolName"),
                                    (left, right) -> left,
                                    LinkedHashMap::new))));
        }
    }

    private boolean hasOrderIdentifierFallback(WorkflowDefinition definition, WorkflowNode tool) {
        for (WorkflowNode condition : definition.nodes()) {
            if (!"condition".equalsIgnoreCase(condition.type())
                    || !isReachable(definition, condition.id(), tool.id())) {
                continue;
            }
            String conditionText = flatten(condition).toLowerCase(Locale.ROOT);
            if (!containsAny(conditionText,
                    "orderid", "order_id", "订单号", "needsorderid", "hasorderid", "orderids")) {
                continue;
            }
            String missingEdgeCondition = missingOrderIdEdgeCondition(condition);
            if (missingEdgeCondition == null) {
                continue;
            }
            String presentEdgeCondition = "true".equals(missingEdgeCondition) ? "false" : "true";
            List<WorkflowEdge> outgoing = definition.edges().stream()
                    .filter(edge -> edge.from().equals(condition.id()))
                    .toList();
            boolean missingBranchAsks = outgoing.stream()
                    .filter(edge -> missingEdgeCondition.equalsIgnoreCase(edge.condition()))
                    .anyMatch(edge -> !branchReaches(definition, edge.to(), tool.id())
                            && branchAsksForOrderId(definition, edge.to()));
            boolean presentBranchReachesTool = outgoing.stream()
                    .filter(edge -> presentEdgeCondition.equalsIgnoreCase(edge.condition()))
                    .anyMatch(edge -> branchReaches(definition, edge.to(), tool.id()));
            if (missingBranchAsks && presentBranchReachesTool) {
                return true;
            }
        }
        return false;
    }

    private String missingOrderIdEdgeCondition(WorkflowNode condition) {
        String left = String.valueOf(condition.config().getOrDefault("left", "")).toLowerCase(Locale.ROOT);
        String operator = String.valueOf(condition.config().getOrDefault("operator", ""))
                .toLowerCase(Locale.ROOT);
        Object right = condition.config().get("right");
        if ("exists".equals(operator)) {
            return "false";
        }
        if ("notexists".equals(operator)) {
            return "true";
        }
        if ("equals".equals(operator)) {
            if (containsAny(left, "needsorderid", "need_order_id")) {
                return Boolean.TRUE.equals(right) || "true".equalsIgnoreCase(String.valueOf(right))
                        ? "true" : "false";
            }
            if (containsAny(left, "hasorderid", "has_order_id")) {
                return Boolean.TRUE.equals(right) || "true".equalsIgnoreCase(String.valueOf(right))
                        ? "false" : "true";
            }
            if (right == null || String.valueOf(right).isBlank()) {
                return "true";
            }
        }
        if ("notequals".equals(operator) && (right == null || String.valueOf(right).isBlank())) {
            return "false";
        }
        return null;
    }

    private boolean branchReaches(WorkflowDefinition definition, String branchTargetId, String targetId) {
        return branchTargetId.equals(targetId) || isReachable(definition, branchTargetId, targetId);
    }

    private boolean branchAsksForOrderId(WorkflowDefinition definition, String branchTargetId) {
        List<WorkflowNode> branch = new ArrayList<>();
        definition.nodes().stream()
                .filter(node -> node.id().equals(branchTargetId))
                .findFirst()
                .ifPresent(branch::add);
        branch.addAll(reachableNodes(definition, branchTargetId));
        return branch.stream()
                .map(this::flatten)
                .map(text -> text.toLowerCase(Locale.ROOT))
                .anyMatch(text -> containsAny(text,
                        "请提供订单号", "补充订单号", "询问订单号", "缺少订单号", "没有订单号",
                        "need_order_id", "provide order", "order number"));
    }

    private void detectToolFailureFallback(WorkflowDefinition definition,
            Map<String, WorkflowGovernanceRule> activeRules,
            List<WorkflowGovernanceFinding> findings) {
        List<WorkflowNode> lookupTools = businessLookupTools(definition);
        if (lookupTools.isEmpty()) {
            return;
        }
        Map<String, Map<String, Object>> coverageByTool = new LinkedHashMap<>();
        for (WorkflowNode tool : lookupTools) {
            boolean noResultHandled = hasToolNoResultFallback(definition, tool);
            boolean continuationConfigured = hasRegisteredExecutionFailureContinuation(tool);
            boolean executionFailureHandled = continuationConfigured
                    && hasToolExecutionFailureFallback(definition, tool);
            if (!noResultHandled || !executionFailureHandled) {
                Map<String, Object> coverage = new LinkedHashMap<>();
                coverage.put("toolName", tool.config().get("toolName"));
                coverage.put("noResultHandled", noResultHandled);
                coverage.put("executionFailureHandled", executionFailureHandled);
                coverage.put("executionFailureReason", executionFailureHandled
                        ? "runtime-continuation-enabled"
                        : continuationConfigured
                                ? "no downstream fallback consumes this tool's failure status"
                                : "continueOnError is not registered and enabled");
                coverageByTool.put(tool.id(), coverage);
            }
        }
        if (!coverageByTool.isEmpty()) {
            addFinding(findings, activeRules, "cs-tool-failure-fallback",
                    "Business lookup does not safely cover both empty results and thrown execution failures.",
                    coverageByTool.keySet(), Map.of("lookupTools", coverageByTool));
        }
    }

    private boolean hasToolNoResultFallback(WorkflowDefinition definition, WorkflowNode tool) {
        for (WorkflowNode downstream : reachableNodes(definition, tool.id())) {
            String text = flatten(downstream).toLowerCase(Locale.ROOT);
            if (nodeConsumesToolResult(definition, tool, downstream)
                    && hasNoResultLanguageForTool(text, tool)) {
                return true;
            }
            if ("condition".equalsIgnoreCase(downstream.type())
                    && nodeConsumesToolResult(definition, tool, downstream)
                    && containsAny(text, "succeeded", "found", "empty", "result")) {
                List<String> falseTargets = definition.edges().stream()
                        .filter(edge -> edge.from().equals(downstream.id())
                                && "false".equalsIgnoreCase(edge.condition()))
                        .map(WorkflowEdge::to)
                        .toList();
                for (String falseTarget : falseTargets) {
                    List<WorkflowNode> falseBranch = new ArrayList<>();
                    definition.nodes().stream()
                            .filter(node -> node.id().equals(falseTarget))
                            .findFirst()
                            .ifPresent(falseBranch::add);
                    falseBranch.addAll(reachableNodes(definition, falseTarget));
                    if (falseBranch.stream()
                            .map(this::flatten)
                            .map(value -> value.toLowerCase(Locale.ROOT))
                            .anyMatch(value -> hasNoResultLanguageForTool(value, tool))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasNoResultLanguage(String text) {
        return containsAny(text,
                "查无结果", "没有查询结果", "未找到订单", "未找到客户", "无匹配结果",
                "no result", "not found", "empty result", "no matching result");
    }

    private boolean hasNoResultLanguageForTool(String text, WorkflowNode tool) {
        if (!hasNoResultLanguage(text)) {
            return false;
        }
        String toolName = String.valueOf(tool.config().getOrDefault("toolName", "")).toLowerCase(Locale.ROOT);
        if (text.contains(tool.id().toLowerCase(Locale.ROOT))
                || (!toolName.isBlank() && text.contains(toolName))) {
            return true;
        }
        if (containsAny(toolName, "order", "logistic", "shipping", "tracking")) {
            return containsAny(text,
                    "order", "orderid", "order id", "订单", "订单号", "物流", "shipment", "shipping", "tracking");
        }
        if (containsAny(toolName, "crm", "customer", "vip", "member")) {
            return containsAny(text, "crm", "customer", "vip", "member", "客户", "会员");
        }
        return false;
    }

    private boolean hasRegisteredExecutionFailureContinuation(WorkflowNode tool) {
        if (!Boolean.TRUE.equals(tool.config().get("continueOnError"))) {
            return false;
        }
        return workflowNodeSchemaRegistry.listSchemas().stream()
                .filter(schema -> "tool".equalsIgnoreCase(schema.type()))
                .flatMap(schema -> schema.configFields().stream())
                .anyMatch(field -> "continueOnError".equals(field.name())
                        && "boolean".equalsIgnoreCase(field.type())
                        && Boolean.FALSE.equals(field.defaultValue()));
    }

    private boolean hasToolExecutionFailureFallback(WorkflowDefinition definition, WorkflowNode tool) {
        for (WorkflowNode condition : reachableNodes(definition, tool.id())) {
            if (!"condition".equalsIgnoreCase(condition.type())) {
                continue;
            }
            String failureEdge = toolFailureEdgeCondition(definition, tool, condition);
            if (failureEdge != null
                    && failureBranchHandlesTool(definition, tool, condition, failureEdge)) {
                return true;
            }
        }
        return false;
    }

    private String toolFailureEdgeCondition(WorkflowDefinition definition, WorkflowNode tool,
            WorkflowNode condition) {
        String field = toolFailureStatusField(definition, tool, condition);
        if (field == null) {
            return null;
        }
        String operator = String.valueOf(condition.config().getOrDefault("operator", "equals"))
                .toLowerCase(Locale.ROOT);
        Object right = condition.config().get("right");
        String normalizedRight = String.valueOf(right).trim().toLowerCase(Locale.ROOT);

        if ("succeeded".equals(field)) {
            if ("equals".equals(operator)) {
                return Boolean.FALSE.equals(right) || "false".equals(normalizedRight) ? "true"
                        : Boolean.TRUE.equals(right) || "true".equals(normalizedRight) ? "false" : null;
            }
            if ("notequals".equals(operator)) {
                return Boolean.TRUE.equals(right) || "true".equals(normalizedRight) ? "true"
                        : Boolean.FALSE.equals(right) || "false".equals(normalizedRight) ? "false" : null;
            }
            return null;
        }
        if ("status".equals(field)) {
            boolean failedValue = Set.of("failed", "failure", "error").contains(normalizedRight);
            boolean successValue = Set.of("succeeded", "success", "ok").contains(normalizedRight);
            if ("equals".equals(operator)) {
                return failedValue ? "true" : successValue ? "false" : null;
            }
            if ("notequals".equals(operator)) {
                return successValue ? "true" : failedValue ? "false" : null;
            }
            return null;
        }
        if ("exists".equals(operator)) {
            return "true";
        }
        if ("notexists".equals(operator)) {
            return "false";
        }
        if ("equals".equals(operator) && normalizedRight.isBlank()) {
            return "false";
        }
        if ("notequals".equals(operator) && normalizedRight.isBlank()) {
            return "true";
        }
        return null;
    }

    private String toolFailureStatusField(WorkflowDefinition definition, WorkflowNode tool,
            WorkflowNode condition) {
        String left = String.valueOf(condition.config().getOrDefault("left", ""))
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        List<String> fields = List.of("succeeded", "status", "errormessage", "errorcategory", "errortype");
        String nodePrefix = "{{nodes." + tool.id().toLowerCase(Locale.ROOT) + ".";
        for (String field : fields) {
            if (left.equals(nodePrefix + field + "}}")) {
                return field;
            }
        }
        boolean directlyPrecedes = definition.edges().stream()
                .anyMatch(edge -> edge.from().equals(tool.id()) && edge.to().equals(condition.id()));
        if (directlyPrecedes) {
            for (String field : fields) {
                if (left.equals("{{lastoutput." + field + "}}")) {
                    return field;
                }
            }
        }
        return null;
    }

    private boolean failureBranchHandlesTool(WorkflowDefinition definition, WorkflowNode tool,
            WorkflowNode condition, String failureEdge) {
        List<WorkflowEdge> outgoing = definition.edges().stream()
                .filter(edge -> edge.from().equals(condition.id()))
                .toList();
        Set<String> otherBranchNodeIds = outgoing.stream()
                .filter(edge -> !failureEdge.equalsIgnoreCase(edge.condition()))
                .flatMap(edge -> branchNodes(definition, edge.to()).stream())
                .map(WorkflowNode::id)
                .collect(Collectors.toSet());
        return outgoing.stream()
                .filter(edge -> failureEdge.equalsIgnoreCase(edge.condition()))
                .flatMap(edge -> branchNodes(definition, edge.to()).stream())
                .filter(node -> !otherBranchNodeIds.contains(node.id()))
                .map(this::flatten)
                .map(text -> text.toLowerCase(Locale.ROOT))
                .anyMatch(text -> hasExecutionFailureFallbackForTool(text, tool));
    }

    private List<WorkflowNode> branchNodes(WorkflowDefinition definition, String targetId) {
        List<WorkflowNode> branch = new ArrayList<>();
        definition.nodes().stream()
                .filter(node -> node.id().equals(targetId))
                .findFirst()
                .ifPresent(branch::add);
        branch.addAll(reachableNodes(definition, targetId));
        return branch;
    }

    private boolean hasExecutionFailureFallbackForTool(String text, WorkflowNode tool) {
        boolean failureLanguage = containsAny(text,
                "tool failed", "lookup failed", "query failed", "execution failed", "call failed",
                "errormessage", "errorcategory", "error type", "error_type",
                "工具失败", "查询失败", "调用失败", "执行失败", "服务不可用", "暂时无法查询");
        boolean remediation = containsAny(text,
                "transfer", "human", "manual", "retry", "try again", "later", "support", "unavailable",
                "转人工", "人工", "重试", "稍后", "请联系", "暂时无法", "无法查询");
        return failureLanguage && remediation && textMatchesToolCapability(text, tool);
    }

    private boolean textMatchesToolCapability(String text, WorkflowNode tool) {
        String toolName = String.valueOf(tool.config().getOrDefault("toolName", "")).toLowerCase(Locale.ROOT);
        if (text.contains(tool.id().toLowerCase(Locale.ROOT))
                || (!toolName.isBlank() && text.contains(toolName))) {
            return true;
        }
        if (containsAny(toolName, "order", "logistic", "shipping", "tracking")) {
            return containsAny(text,
                    "order", "orderid", "order id", "订单", "订单号", "物流", "shipment", "shipping", "tracking");
        }
        if (containsAny(toolName, "crm", "customer", "vip", "member")) {
            return containsAny(text, "crm", "customer", "vip", "member", "客户", "会员");
        }
        return false;
    }

    private List<WorkflowNode> reachableNodes(WorkflowDefinition definition, String sourceId) {
        Map<String, List<String>> outgoing = definition.edges().stream()
                .collect(Collectors.groupingBy(WorkflowEdge::from,
                        LinkedHashMap::new,
                        Collectors.mapping(WorkflowEdge::to, Collectors.toList())));
        Map<String, WorkflowNode> nodesById = definition.nodes().stream()
                .collect(Collectors.toMap(WorkflowNode::id, Function.identity()));
        Deque<String> pending = new ArrayDeque<>(outgoing.getOrDefault(sourceId, List.of()));
        Set<String> visited = new LinkedHashSet<>();
        List<WorkflowNode> reachable = new ArrayList<>();
        while (!pending.isEmpty()) {
            String currentId = pending.removeFirst();
            if (!visited.add(currentId)) {
                continue;
            }
            WorkflowNode node = nodesById.get(currentId);
            if (node != null) {
                reachable.add(node);
            }
            pending.addAll(outgoing.getOrDefault(currentId, List.of()));
        }
        return reachable;
    }

    private List<WorkflowNode> businessLookupTools(WorkflowDefinition definition) {
        return definition.nodes().stream()
                .filter(node -> "tool".equalsIgnoreCase(node.type()))
                .filter(node -> {
                    String toolName = String.valueOf(node.config().getOrDefault("toolName", ""))
                            .toLowerCase(Locale.ROOT);
                    return containsAny(toolName, "order", "logistic", "shipping", "crm", "customer");
                })
                .toList();
    }

    private List<WorkflowNode> orderLookupTools(WorkflowDefinition definition) {
        return definition.nodes().stream()
                .filter(node -> "tool".equalsIgnoreCase(node.type()))
                .filter(node -> {
                    String toolName = String.valueOf(node.config().getOrDefault("toolName", ""))
                            .toLowerCase(Locale.ROOT);
                    return containsAny(toolName, "order", "logistic", "shipping", "tracking");
                })
                .toList();
    }

    private Map<String, Object> schemaProperties(Object outputSchema) {
        if (!(outputSchema instanceof Map<?, ?> schema)
                || !(schema.get("properties") instanceof Map<?, ?> properties)) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        properties.forEach((key, value) -> normalized.put(String.valueOf(key), value));
        return normalized;
    }

    private List<String> stringValues(Object value) {
        List<String> values = new ArrayList<>();
        collectStringValues(value, values);
        return values;
    }

    private void collectStringValues(Object value, List<String> values) {
        if (value instanceof String text) {
            values.add(text);
        }
        else if (value instanceof Map<?, ?> map) {
            map.values().forEach(child -> collectStringValues(child, values));
        }
        else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(child -> collectStringValues(child, values));
        }
    }

    private String flatten(WorkflowDefinition definition) {
        StringBuilder builder = new StringBuilder();
        definition.nodes().forEach(node -> builder.append(flatten(node)).append('\n'));
        definition.edges().forEach(edge -> builder.append(edge.from()).append(' ')
                .append(edge.to()).append(' ')
                .append(normalize(edge.condition())).append(' ')
                .append(normalize(edge.label())).append(' ')
                .append(normalize(edge.route())).append('\n'));
        return builder.toString();
    }

    private String flatten(WorkflowNode node) {
        StringBuilder builder = new StringBuilder();
        builder.append(node.id()).append(' ')
                .append(node.type()).append(' ')
                .append(normalize(node.label())).append(' ')
                .append(normalize(node.route())).append(' ');
        appendValue(builder, node.config());
        return builder.toString();
    }

    private void appendValue(StringBuilder builder, Object value) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                builder.append(key).append(' ');
                appendValue(builder, child);
            });
        }
        else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(child -> appendValue(builder, child));
        }
        else if (value != null) {
            builder.append(value).append(' ');
        }
    }

    private void addFinding(List<WorkflowGovernanceFinding> findings,
            Map<String, WorkflowGovernanceRule> activeRules,
            String ruleId,
            String message,
            Collection<String> nodeIds,
            Map<String, Object> evidence) {
        if (!activeRules.containsKey(ruleId)) {
            return;
        }
        findings.add(finding(activeRules, ruleId, message, nodeIds, evidence));
    }

    private WorkflowGovernanceFinding finding(Map<String, WorkflowGovernanceRule> activeRules,
            String ruleId,
            String message,
            Collection<String> nodeIds,
            Map<String, Object> evidence) {
        WorkflowGovernanceRule rule = activeRules.get(ruleId);
        if (rule == null) {
            rule = workflowRuleCatalog.allPacks().stream()
                    .flatMap(pack -> pack.rules().stream())
                    .filter(candidate -> candidate.id().equals(ruleId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown workflow governance rule: " + ruleId));
        }
        WorkflowGovernanceFinding.Severity severity = "error".equalsIgnoreCase(rule.severity())
                ? WorkflowGovernanceFinding.Severity.BLOCK
                : WorkflowGovernanceFinding.Severity.WARN;
        return new WorkflowGovernanceFinding(
                rule.id(),
                severity,
                WorkflowGovernanceFinding.Phase.STATIC,
                message,
                nodeIds == null ? List.of() : List.copyOf(nodeIds),
                rule.repairHint(),
                evidence);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value;
    }
}
