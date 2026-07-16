package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.tool.ToolExecutionLog;
import com.example.agentdemo.tool.ToolGatewayService;
import com.example.agentdemo.workflow.report.ReportExportCommand;
import com.example.agentdemo.workflow.report.ReportFormat;
import com.example.agentdemo.workflow.report.ReportRenderRequest;
import com.example.agentdemo.workflow.report.WorkflowReportExportService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class WorkflowNodeExecutor {

    private static final String WORKFLOW_SYSTEM_PROMPT = """
            You are a workflow LLM node. Use the workflow input and retrieved context when available.
            If context is missing, say what is missing instead of inventing details.
            """;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final RagService ragService;
    private final AiModelService aiModelService;
    private final ToolGatewayService toolGatewayService;
    private final WorkflowVariableResolver variableResolver;
    private final AlibabaRuntimePolicy alibabaRuntimePolicy;
    private final WorkflowInlineExecutionService inlineExecutionService;
    private final WorkflowHttpRequestService httpRequestService;
    private final WorkflowReportExportService reportExportService;

    public WorkflowNodeExecutor(RagService ragService, AiModelService aiModelService,
            ToolGatewayService toolGatewayService, WorkflowVariableResolver variableResolver,
            AlibabaRuntimePolicy alibabaRuntimePolicy, WorkflowInlineExecutionService inlineExecutionService) {
        this(ragService, aiModelService, toolGatewayService, variableResolver, alibabaRuntimePolicy,
                inlineExecutionService, null, null);
    }

    public WorkflowNodeExecutor(RagService ragService, AiModelService aiModelService,
            ToolGatewayService toolGatewayService, WorkflowVariableResolver variableResolver,
            AlibabaRuntimePolicy alibabaRuntimePolicy, WorkflowInlineExecutionService inlineExecutionService,
            WorkflowHttpRequestService httpRequestService) {
        this(ragService, aiModelService, toolGatewayService, variableResolver, alibabaRuntimePolicy,
                inlineExecutionService, httpRequestService, null);
    }

    @Autowired
    public WorkflowNodeExecutor(RagService ragService, AiModelService aiModelService,
            ToolGatewayService toolGatewayService, WorkflowVariableResolver variableResolver,
            AlibabaRuntimePolicy alibabaRuntimePolicy, WorkflowInlineExecutionService inlineExecutionService,
            WorkflowHttpRequestService httpRequestService, WorkflowReportExportService reportExportService) {
        this.ragService = ragService;
        this.aiModelService = aiModelService;
        this.toolGatewayService = toolGatewayService;
        this.variableResolver = variableResolver;
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
        this.inlineExecutionService = inlineExecutionService;
        this.httpRequestService = httpRequestService;
        this.reportExportService = reportExportService;
    }

    Object execute(String runId, WorkflowNode node, WorkflowExecutionState state) {
        Object output = switch (normalizeType(node)) {
            case "start" -> executeStart(state);
            case "retriever" -> executeRetriever(runId, node, state);
            case "tavily_search" -> executeTavilySearch(node, state);
            case "llm" -> executeLlm(node, state);
            case "tool" -> executeTool(node, state);
            case "http_request" -> executeHttpRequest(node, state);
            case "report_export" -> executeReportExport(runId, node, state);
            case "custom" -> executeCustom(node, state);
            case "condition" -> executeCondition(node, state);
            case "parallel" -> executeParallel(state);
            case "join" -> executeJoin(state);
            case "variable_aggregator" -> executeVariableAggregator(node, state);
            case "end" -> executeEnd(state);
            case "loop" -> inlineExecutionService.executeLoop(runId, node, state);
            case "loop_back" -> executeLoopBack(node, state);
            case "subgraph" -> inlineExecutionService.executeSubgraph(runId, node, state);
            case "dynamic" -> inlineExecutionService.executeDynamic(runId, node, state);
            default -> throw new BusinessException("WORKFLOW_UNSUPPORTED", "Unsupported node type: " + node.type());
        };
        applyWriteState(node, state);
        return output;
    }

    WorkflowInlineExecutionService inlineExecutionService() {
        return inlineExecutionService;
    }

    Map<String, Object> nodeInput(WorkflowNode node, WorkflowExecutionState state) {
        Map<String, Object> input = orderedMap();
        input.put("nodeId", node.id());
        input.put("nodeType", normalizeType(node));
        input.put("config", node.config());
        input.put("workflowInput", state.input());
        input.put("lastOutput", state.lastOutput());
        input.put("nodeOutputs", state.nodeOutputs());
        return input;
    }

    String normalizeType(WorkflowNode node) {
        return node.type().toLowerCase(Locale.ROOT);
    }

    Map<String, Object> errorOutput(RuntimeException ex) {
        Map<String, Object> output = orderedMap();
        output.put("error", ex.getMessage());
        return output;
    }

    private Object executeStart(WorkflowExecutionState state) {
        state.setLastOutput(state.input());
        return state.input();
    }

    private Object executeRetriever(String runId, WorkflowNode node, WorkflowExecutionState state) {
        String query = variableResolver.renderString(configString(node, "query", state.primaryInput()), state);
        int topK = configInt(node, "topK", 3);
        List<RetrievedContext> contexts = ragService.retrieve(query, topK, runId);
        state.setRetrievedContext(contexts);
        Map<String, Object> output = orderedMap();
        output.put("query", query);
        output.put("topK", topK);
        output.put("retrievedContext", contexts);
        state.setLastOutput(output);
        return output;
    }

    private Object executeLlm(WorkflowNode node, WorkflowExecutionState state) {
        String promptTemplate = configString(node, "prompt",
                "Answer the workflow input using this context: {{context}}\nInput: {{input}}");
        String prompt = variableResolver.renderString(promptTemplate, state);
        String systemPrompt = workflowSystemPrompt(node);
        String configuredModel = configString(node, "model", null);
        AiModelResult result = StringUtils.hasText(configuredModel)
                ? aiModelService.generateWithModel(systemPrompt, prompt, configuredModel)
                : aiModelService.generate(systemPrompt, prompt);
        String answer = resolveAnswer(prompt, state, result);

        Map<String, Object> output = orderedMap();
        output.put("prompt", prompt);
        output.put("answer", answer);
        Object parsed = parseJsonAnswer(answer, requiresJsonOutput(node), node);
        if (parsed != null) {
            WorkflowOutputSchemaValidator.validate(node.config().get("outputSchema"), parsed)
                    .ifPresent(error -> {
                        throw invalidLlmOutput(node, "violates outputSchema: " + error);
            });
            output.put("parsed", parsed);
        }
        state.setAnswer(answer);
        output.put("model", outputModel(configuredModel, result));
        output.put("fallback", result.fallback());
        output.put("errorMessage", result.errorMessage());
        if (result.tokenUsage() != null) {
            output.put("tokenUsage", result.tokenUsage());
        }
        state.setLastOutput(output);
        return output;
    }

    private Object executeTavilySearch(WorkflowNode node, WorkflowExecutionState state) {
        Map<String, Object> arguments = orderedMap();
        arguments.put("query", variableResolver.renderString(requiredConfigString(node, "query"), state));
        arguments.put("search_depth", configString(node, "searchDepth", "basic"));
        arguments.put("topic", configString(node, "topic", "general"));
        arguments.put("max_results", configInt(node, "maxResults", 5));
        arguments.put("include_answer", configBoolean(node, "includeAnswer", false));
        arguments.put("include_raw_content", configBoolean(node, "includeRawContent", false));
        String timeRange = configString(node, "timeRange", null);
        if (StringUtils.hasText(timeRange)) {
            arguments.put("time_range", timeRange);
        }
        putRenderedList(arguments, "include_domains", node.config().get("includeDomains"), state);
        putRenderedList(arguments, "exclude_domains", node.config().get("excludeDomains"), state);

        ToolExecutionLog log = toolGatewayService.execute("tavilySearch", arguments);
        state.addToolCall(log);
        if (!log.succeeded()) {
            throw new WorkflowNodeExecutionException(log.errorCategory(), log.errorMessage(), log);
        }
        state.setLastOutput(log.output());
        return log.output();
    }

    private void putRenderedList(Map<String, Object> target, String key, Object configured,
            WorkflowExecutionState state) {
        Object rendered = renderArgument(configured == null ? List.of() : configured, state);
        if (rendered instanceof Iterable<?> values) {
            List<Object> items = new ArrayList<>();
            values.forEach(items::add);
            if (!items.isEmpty()) {
                target.put(key, List.copyOf(items));
            }
        }
    }

    private String workflowSystemPrompt(WorkflowNode node) {
        String structuredContract = structuredOutputContract(node);
        if (!StringUtils.hasText(structuredContract)) {
            return WORKFLOW_SYSTEM_PROMPT;
        }
        return WORKFLOW_SYSTEM_PROMPT + "\n" + structuredContract;
    }

    private String structuredOutputContract(WorkflowNode node) {
        if (!requiresJsonOutput(node)) {
            return "";
        }
        StringBuilder contract = new StringBuilder("""
                Structured output contract:
                Return exactly one valid JSON object. Do not wrap it in Markdown, prose, or code fences.
                Do not omit required fields. If a required explanatory field is requested and the evidence is simple,
                fill it with a short reason instead of leaving it out.
                """);
        Object outputSchema = node.config().get("outputSchema");
        if (outputSchema instanceof Map<?, ?> schema && !schema.isEmpty()) {
            contract.append("The JSON object must satisfy this schema:\n")
                    .append(compactJson(schema))
                    .append('\n');
        }
        return contract.toString();
    }

    private String compactJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }

    private Object executeTool(WorkflowNode node, WorkflowExecutionState state) {
        String toolName = requiredConfigString(node, "toolName");
        Map<String, Object> arguments = toolArguments(node, state, toolName);
        ToolExecutionLog log = WorkflowEvaluationFixtures.failedToolCall(state.input(), toolName, arguments)
                .orElseGet(() -> toolGatewayService.execute(toolName, arguments));
        state.addToolCall(log);
        if (!log.succeeded()) {
            if (configBoolean(node, "continueOnError", false)) {
                Map<String, Object> output = continuedToolFailureOutput(log);
                state.setLastOutput(output);
                return output;
            }
            throw new WorkflowNodeExecutionException(log.errorCategory(), log.errorMessage(), log);
        }
        state.setLastOutput(log.output());
        return log;
    }

    private Object executeHttpRequest(WorkflowNode node, WorkflowExecutionState state) {
        if (httpRequestService == null) {
            throw new BusinessException("WORKFLOW_HTTP_UNAVAILABLE", "HTTP request service is unavailable");
        }
        Map<String, Object> output = httpRequestService.execute(node, state);
        state.setLastOutput(output);
        return output;
    }

    private Object executeReportExport(String runId, WorkflowNode node, WorkflowExecutionState state) {
        if (reportExportService == null) {
            throw new BusinessException("REPORT_EXPORT_UNAVAILABLE", "Report export service is unavailable");
        }
        String content = variableResolver.renderString(requiredConfigString(node, "content"), state);
        List<ReportFormat> formats = new ArrayList<>();
        Object configuredFormats = node.config().getOrDefault("formats", List.of("pdf"));
        if (configuredFormats instanceof Iterable<?> values) {
            values.forEach(value -> formats.add(ReportFormat.fromConfig(value)));
        }
        ReportRenderRequest renderRequest = new ReportRenderRequest(
                variableResolver.renderString(configString(node, "title", ""), state),
                variableResolver.renderString(configString(node, "author", ""), state),
                variableResolver.renderString(configString(node, "organization", ""), state),
                content,
                configString(node, "theme", "business"),
                configString(node, "paperSize", "A4"),
                configString(node, "orientation", "portrait"),
                configBoolean(node, "includeToc", true),
                configBoolean(node, "includePageNumbers", true));
        ReportExportCommand command = new ReportExportCommand(runId, node.id(), state.ownerId(),
                state.originAppId(), variableResolver.renderString(configString(node, "fileName", "report"), state),
                configInt(node, "retentionDays", 30), renderRequest, List.copyOf(formats));
        Map<String, Object> output = reportExportService.export(command).toOutput();
        state.setLastOutput(output);
        return output;
    }

    private Object executeCustom(WorkflowNode node, WorkflowExecutionState state) {
        String mode = configString(node, "mode", "ai").toLowerCase(Locale.ROOT);
        Map<String, Object> inputs = customInputs(node, state);
        if ("template".equals(mode)) {
            Object rendered = variableResolver.renderDeep(node.config().getOrDefault("template", ""), state);
            Map<String, Object> output = orderedMap();
            output.put("mode", "template");
            output.put("inputs", inputs);
            output.put("output", rendered);
            state.setLastOutput(output);
            return output;
        }
        if (!"ai".equals(mode)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Unsupported custom node mode: " + mode);
        }

        String instruction = requiredConfigString(node, "instruction");
        Map<String, Object> llmConfig = orderedMap();
        llmConfig.put("prompt", instruction + "\n\n命名输入（JSON）：\n" + compactJson(inputs)
                + "\n\n请只根据这些输入完成任务；缺少证据时明确返回缺失，不得补造事实。");
        copyConfig(node, llmConfig, "model");
        copyConfig(node, llmConfig, "outputMode");
        copyConfig(node, llmConfig, "outputSchema");
        Object llmResult = executeLlm(new WorkflowNode(node.id(), "llm", llmConfig), state);
        if (!(llmResult instanceof Map<?, ?> result)) {
            return llmResult;
        }
        Map<String, Object> output = orderedMap();
        output.put("mode", "ai");
        output.put("inputs", inputs);
        result.forEach((key, value) -> output.put(String.valueOf(key), value));
        output.put("output", result.containsKey("parsed") ? result.get("parsed") : result.get("answer"));
        state.setLastOutput(output);
        return output;
    }

    private Map<String, Object> customInputs(WorkflowNode node, WorkflowExecutionState state) {
        Object configured = node.config().getOrDefault("inputs", Map.of());
        if (!(configured instanceof Map<?, ?> map)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + ".inputs must be object");
        }
        Object rendered = variableResolver.renderDeep(map, state);
        if (!(rendered instanceof Map<?, ?> renderedMap)) {
            return Map.of();
        }
        Map<String, Object> inputs = orderedMap();
        renderedMap.forEach((key, value) -> inputs.put(String.valueOf(key), value));
        return inputs;
    }

    private void copyConfig(WorkflowNode source, Map<String, Object> target, String key) {
        if (source.config().containsKey(key)) {
            target.put(key, source.config().get(key));
        }
    }

    private Map<String, Object> continuedToolFailureOutput(ToolExecutionLog log) {
        Map<String, Object> output = orderedMap();
        output.put("status", "FAILED");
        output.put("succeeded", false);
        output.put("toolName", log.toolName());
        output.put("output", log.output());
        output.put("errorMessage", log.errorMessage());
        output.put("errorCategory", log.errorCategory());
        output.put("errorType", log.errorType());
        output.put("toolExecutionLog", log);
        return output;
    }

    private Object executeCondition(WorkflowNode node, WorkflowExecutionState state) {
        if (hasCompositeConditions(node)) {
            return executeCompositeCondition(node, state);
        }
        String left = variableResolver.renderString(configString(node, "left", "{{input}}"), state);
        String operator = configString(node, "operator", "contains").toLowerCase(Locale.ROOT);
        Object right = renderArgument(node.config().getOrDefault("right", ""), state);
        boolean caseSensitive = configBoolean(node, "caseSensitive", false);
        boolean result = evaluateCondition(left, operator, right, caseSensitive);
        state.setLastConditionResult(result);

        Map<String, Object> output = orderedMap();
        output.put("left", left);
        output.put("operator", operator);
        output.put("right", right);
        output.put("caseSensitive", caseSensitive);
        output.put("result", result);
        state.setLastOutput(output);
        return output;
    }

    private boolean hasCompositeConditions(WorkflowNode node) {
        Object rawConditions = node.config().get("conditions");
        if (rawConditions == null) {
            return false;
        }
        if (!(rawConditions instanceof Iterable<?> conditions)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + ".conditions must be array");
        }
        return conditions.iterator().hasNext();
    }

    private Object executeCompositeCondition(WorkflowNode node, WorkflowExecutionState state) {
        List<Map<String, Object>> conditionOutputs = new ArrayList<>();
        Object rawConditions = node.config().get("conditions");
        if (!(rawConditions instanceof Iterable<?> conditions)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + ".conditions must be array");
        }
        for (Object rawCondition : conditions) {
            if (!(rawCondition instanceof Map<?, ?> conditionConfig)) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Config " + node.id() + ".conditions items must be object");
            }
            conditionOutputs.add(evaluateConfiguredCondition(conditionConfig, state));
        }
        if (conditionOutputs.isEmpty()) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + ".conditions must not be empty");
        }

        String mode = configString(node, "mode", "all").toLowerCase(Locale.ROOT);
        boolean result = switch (mode) {
            case "all" -> conditionOutputs.stream().allMatch(condition -> Boolean.TRUE.equals(condition.get("result")));
            case "any" -> conditionOutputs.stream().anyMatch(condition -> Boolean.TRUE.equals(condition.get("result")));
            default -> throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Unsupported condition mode: " + mode);
        };
        state.setLastConditionResult(result);

        Map<String, Object> output = orderedMap();
        output.put("mode", mode);
        output.put("conditions", conditionOutputs);
        output.put("result", result);
        state.setLastOutput(output);
        return output;
    }

    private Map<String, Object> evaluateConfiguredCondition(Map<?, ?> conditionConfig, WorkflowExecutionState state) {
        String left = variableResolver.renderString(configString(conditionConfig, "left", "{{input}}"), state);
        String operator = configString(conditionConfig, "operator", "contains").toLowerCase(Locale.ROOT);
        Object configuredRight = conditionConfig.containsKey("right") ? conditionConfig.get("right") : "";
        Object right = renderArgument(configuredRight, state);
        boolean caseSensitive = configBoolean(conditionConfig, "caseSensitive", false);
        boolean result = evaluateCondition(left, operator, right, caseSensitive);

        Map<String, Object> output = orderedMap();
        output.put("left", left);
        output.put("operator", operator);
        output.put("right", right);
        output.put("caseSensitive", caseSensitive);
        output.put("result", result);
        return output;
    }

    private Object executeParallel(WorkflowExecutionState state) {
        Map<String, Object> output = orderedMap();
        output.put("status", "READY");
        output.put("input", state.lastOutput());
        state.setLastOutput(output);
        return output;
    }

    private Object executeJoin(WorkflowExecutionState state) {
        Object output = state.lastOutput() == null ? Map.of() : state.lastOutput();
        state.setLastOutput(output);
        return output;
    }

    private Object executeVariableAggregator(WorkflowNode node, WorkflowExecutionState state) {
        String mode = configString(node, "mode", "single").toLowerCase(Locale.ROOT);
        Map<String, Object> output = orderedMap();
        if ("single".equals(mode)) {
            Object value = firstAvailableValue(node.id(), "output", node.config().get("variables"),
                    configString(node, "outputType", "string"), state);
            output.put("output", value);
        }
        else if ("groups".equals(mode)) {
            Object rawGroups = node.config().get("groups");
            if (!(rawGroups instanceof Iterable<?> groups)) {
                throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                        "Config " + node.id() + ".groups must be array");
            }
            for (Object rawGroup : groups) {
                if (!(rawGroup instanceof Map<?, ?> group)) {
                    throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                            "Config " + node.id() + ".groups items must be object");
                }
                String key = configString(group, "key", null);
                if (!StringUtils.hasText(key)) {
                    throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                            "Variable aggregator group key is required: " + node.id());
                }
                Object value = firstAvailableValue(node.id(), key, group.get("variables"),
                        configString(group, "outputType", "string"), state);
                output.put(key, Map.of("output", value));
            }
        }
        else {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Unsupported variable aggregator mode: " + mode);
        }
        state.setLastOutput(output);
        return output;
    }

    private Object firstAvailableValue(String nodeId, String groupKey, Object configuredVariables,
            String outputType, WorkflowExecutionState state) {
        if (!(configuredVariables instanceof Iterable<?> variables)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Variable aggregator " + nodeId + "." + groupKey + " variables must be array");
        }
        for (Object configuredVariable : variables) {
            WorkflowResolvedValue resolved = variableResolver.resolveReference(String.valueOf(configuredVariable), state);
            if (!resolved.present() || resolved.value() == null) {
                continue;
            }
            validateAggregatedValueType(nodeId, groupKey, outputType, resolved.value());
            return resolved.value();
        }
        throw new BusinessException("WORKFLOW_VARIABLE_UNAVAILABLE",
                "Variable aggregator " + nodeId + " group " + groupKey + " has no available upstream value");
    }

    private void validateAggregatedValueType(String nodeId, String groupKey, String outputType, Object value) {
        boolean matches = switch (outputType.toLowerCase(Locale.ROOT)) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof Iterable<?> || value.getClass().isArray();
            default -> false;
        };
        if (!matches) {
            throw new BusinessException("WORKFLOW_VARIABLE_TYPE_MISMATCH",
                    "Variable aggregator " + nodeId + " group " + groupKey
                            + " expected " + outputType + " but received " + value.getClass().getSimpleName());
        }
    }

    private Object executeLoopBack(WorkflowNode node, WorkflowExecutionState state) {
        Map<String, Object> output = orderedMap();
        output.put("status", "LOOP_BACK");
        output.put("nodeId", node.id());
        state.setLastOutput(output);
        return output;
    }

    boolean evaluateCondition(String left, String operator, Object right, boolean caseSensitive) {
        String leftText = left == null ? "" : left;
        String rightText = right == null ? "" : String.valueOf(right);
        String comparableLeft = caseSensitive ? leftText : leftText.toLowerCase(Locale.ROOT);
        String comparableRight = caseSensitive ? rightText : rightText.toLowerCase(Locale.ROOT);
        return switch (operator) {
            case "equals" -> comparableLeft.equals(comparableRight);
            case "notequals" -> !comparableLeft.equals(comparableRight);
            case "contains" -> comparableLeft.contains(comparableRight);
            case "notcontains" -> !comparableLeft.contains(comparableRight);
            case "startswith" -> comparableLeft.startsWith(comparableRight);
            case "endswith" -> comparableLeft.endsWith(comparableRight);
            case "exists" -> StringUtils.hasText(leftText);
            case "notexists" -> !StringUtils.hasText(leftText);
            case "greaterthan" -> compareNumbers(leftText, rightText) > 0;
            case "lessthan" -> compareNumbers(leftText, rightText) < 0;
            default -> throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Unsupported condition operator: " + operator);
        };
    }

    private int compareNumbers(String leftText, String rightText) {
        try {
            double leftValue = Double.parseDouble(leftText);
            double rightValue = Double.parseDouble(rightText);
            return Double.compare(leftValue, rightValue);
        }
        catch (NumberFormatException ex) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Numeric comparison requires numeric left/right values");
        }
    }

    private Map<String, Object> toolArguments(WorkflowNode node, WorkflowExecutionState state, String toolName) {
        Map<String, Object> arguments = orderedMap();
        Object configuredArguments = node.config().get("arguments");
        if (configuredArguments instanceof Map<?, ?> argumentMap) {
            for (Map.Entry<?, ?> entry : argumentMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    arguments.put(key, renderArgument(entry.getValue(), state));
                }
            }
        }
        if ("calculate".equals(toolName) && !arguments.containsKey("expression")) {
            arguments.put("expression", variableResolver.renderString(configString(node, "expression", ""), state));
        }
        return arguments;
    }

    private Object renderArgument(Object value, WorkflowExecutionState state) {
        return variableResolver.renderValue(value, state);
    }

    private void applyWriteState(WorkflowNode node, WorkflowExecutionState state) {
        Object configuredWriteState = node.config().get("writeState");
        if (!(configuredWriteState instanceof Map<?, ?> writeState)) {
            return;
        }
        for (Map.Entry<?, ?> entry : writeState.entrySet()) {
            if (entry.getKey() instanceof String key && StringUtils.hasText(key)) {
                state.setStateVariable(key, renderArgument(entry.getValue(), state));
            }
        }
    }

    private Object executeEnd(WorkflowExecutionState state) {
        Object output = state.answer() != null ? finalAnswerOutput(state) : state.lastOutput();
        state.setFinalOutput(output);
        return output;
    }

    private Map<String, Object> finalAnswerOutput(WorkflowExecutionState state) {
        Map<String, Object> output = orderedMap();
        output.put("answer", state.answer());
        output.put("retrievedContext", state.retrievedContext());
        output.put("toolCalls", state.toolCalls());
        return output;
    }

    private String resolveAnswer(String prompt, WorkflowExecutionState state, AiModelResult result) {
        if (!result.fallback()) {
            if (!StringUtils.hasText(result.answer())) {
                throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                        "Alibaba LLM returned an empty answer for workflow LLM nodes");
            }
            return result.answer();
        }
        throw new BusinessException("ALIBABA_LLM_UNAVAILABLE",
                "Alibaba LLM is required for workflow LLM nodes");
    }

    private Object parseJsonAnswer(String answer, boolean required, WorkflowNode node) {
        String text = answer == null ? "" : answer.trim();
        if (!(text.startsWith("{") || text.startsWith("["))) {
            if (required) {
                throw invalidLlmOutput(node, "must be valid JSON object or array");
            }
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(text, Object.class);
        }
        catch (JsonProcessingException ex) {
            if (required) {
                throw invalidLlmOutput(node, "must be valid JSON object or array: " + ex.getOriginalMessage());
            }
            return null;
        }
    }

    private boolean requiresJsonOutput(WorkflowNode node) {
        String outputMode = configString(node, "outputMode", "text");
        return "json".equalsIgnoreCase(outputMode) || hasOutputSchema(node);
    }

    private boolean hasOutputSchema(WorkflowNode node) {
        Object outputSchema = node.config().get("outputSchema");
        return outputSchema instanceof Map<?, ?> schema && !schema.isEmpty();
    }

    private BusinessException invalidLlmOutput(WorkflowNode node, String message) {
        return new BusinessException("WORKFLOW_LLM_OUTPUT_INVALID",
                "LLM node " + node.id() + " output " + message);
    }

    private String outputModel(String configuredModel, AiModelResult result) {
        if (result.tokenUsage() != null && StringUtils.hasText(result.tokenUsage().model())) {
            return result.tokenUsage().model();
        }
        if (StringUtils.hasText(configuredModel)) {
            return configuredModel;
        }
        return aiModelService.modelName();
    }

    private String configString(WorkflowNode node, String key, String defaultValue) {
        return configString(node.config(), key, defaultValue);
    }

    private String requiredConfigString(WorkflowNode node, String key) {
        String value = configString(node, key, null);
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED",
                    "Config " + node.id() + "." + key + " is required");
        }
        return value;
    }

    private String configString(Map<?, ?> config, String key, String defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private int configInt(WorkflowNode node, String key, int defaultValue) {
        Object value = node.config().get(key);
        if (value instanceof Number number) {
            return clamp(number.intValue(), 1, 20);
        }
        if (value != null) {
            try {
                return clamp(Integer.parseInt(String.valueOf(value)), 1, 20);
            }
            catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private boolean configBoolean(WorkflowNode node, String key, boolean defaultValue) {
        return configBoolean(node.config(), key, defaultValue);
    }

    private boolean configBoolean(Map<?, ?> config, String key, boolean defaultValue) {
        Object value = config.get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

}
