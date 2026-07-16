package com.example.agentdemo.workflow;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class WorkflowNodeSchemaRegistry {

    private static final List<String> TEMPLATE_VARIABLES = List.of(
            "{{input}}",
            "{{input.field}}",
            "{{context}}",
            "{{lastOutput}}",
            "{{lastOutput.field}}",
            "{{state.field}}",
            "{{nodes.nodeId}}",
            "{{nodes.nodeId.field}}",
            "{{toolResult}}",
            "{{answer}}");

    /** Palette group per node type (Basic / LLM / Knowledge / Tools / Flow Control / Advanced). */
    private static final Map<String, String> NODE_GROUPS = Map.ofEntries(
            Map.entry("start", "Basic"),
            Map.entry("end", "Basic"),
            Map.entry("llm", "LLM"),
            Map.entry("retriever", "Knowledge"),
            Map.entry("tavily_search", "Knowledge"),
            Map.entry("tool", "Tools"),
            Map.entry("http_request", "Tools"),
            Map.entry("report_export", "Tools"),
            Map.entry("custom", "Tools"),
            Map.entry("dynamic", "Tools"),
            Map.entry("condition", "Flow Control"),
            Map.entry("parallel", "Flow Control"),
            Map.entry("join", "Flow Control"),
            Map.entry("variable_aggregator", "Flow Control"),
            Map.entry("loop", "Flow Control"),
            Map.entry("loop_back", "Flow Control"),
            Map.entry("subgraph", "Advanced"));

    private final List<WorkflowNodeSchema> schemas = List.of(
            startSchema(),
            retrieverSchema(),
            tavilySearchSchema(),
            llmSchema(),
            toolSchema(),
            httpRequestSchema(),
            reportExportSchema(),
            customSchema(),
            conditionSchema(),
            parallelSchema(),
            joinSchema(),
            variableAggregatorSchema(),
            loopSchema(),
            loopBackSchema(),
            subgraphSchema(),
            dynamicSchema(),
            endSchema()).stream()
            .map(schema -> schema.withGroup(NODE_GROUPS.getOrDefault(schema.type(), "Advanced")))
            .toList();

    public List<WorkflowNodeSchema> listSchemas() {
        return schemas;
    }

    public Optional<WorkflowNodeSchema> findSchema(String type) {
        return schemas.stream()
                .filter(schema -> schema.type().equals(type))
                .findFirst();
    }

    public String generationCatalog() {
        StringBuilder catalog = new StringBuilder();
        for (WorkflowNodeSchema schema : schemas) {
            List<String> configNames = schema.configFields().stream()
                    .map(WorkflowNodeConfigField::name)
                    .toList();
            catalog.append("- ").append(schema.type())
                    .append(": ").append(schema.description())
                    .append(" Config fields: ").append(configNames)
                    .append(" Output: ").append(schema.outputDescription())
                    .append('\n');
        }
        return catalog.toString();
    }

    private WorkflowNodeSchema startSchema() {
        return new WorkflowNodeSchema(
                "start",
                "Start",
                "Workflow entry node. It copies request input into workflow state.",
                withExecutionControls(List.of()),
                List.of(),
                "The original workflow input map.");
    }

    private WorkflowNodeSchema retrieverSchema() {
        return new WorkflowNodeSchema(
                "retriever",
                "Retriever",
                "Retrieves document context through RagService. The active retriever is selected by RAG config.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField(
                                "query",
                                "string",
                                false,
                                null,
                                "Optional query template. Defaults to the primary workflow input message. Supports workflow variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "topK",
                                "integer",
                                false,
                                3,
                                "Maximum retrieved context count.",
                                orderedMap("min", 1, "max", 20)))),
                TEMPLATE_VARIABLES,
                "A map containing query, topK and retrievedContext.");
    }

    private WorkflowNodeSchema llmSchema() {
        return new WorkflowNodeSchema(
                "llm",
                "LLM",
                "Calls AiModelService with a prompt rendered from workflow state.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField(
                                "prompt",
                                "string",
                                false,
                                "Answer the workflow input using this context: {{context}}\nInput: {{input}}",
                                "Prompt template sent to the model. Supports workflow variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "model",
                                "string",
                                false,
                                null,
                                "Optional DashScope chat model override for this LLM node.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "outputMode",
                                "string",
                                false,
                                "text",
                                "Controls whether the LLM output is free text or must be valid JSON.",
                                orderedMap("allowedValues", List.of("text", "json"))),
                        new WorkflowNodeConfigField(
                                "outputSchema",
                                "object",
                                false,
                                Map.of(),
                                "Optional JSON Schema subset for validating parsed JSON output before downstream routing.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "autoStructuredOutputContract",
                                "string",
                                false,
                                null,
                                "Internal marker for an automatically managed structured output contract.",
                                Map.of()))),
                TEMPLATE_VARIABLES,
                "A map containing prompt, answer, parsed, model, tokenUsage, fallback and errorMessage.");
    }

    private WorkflowNodeSchema tavilySearchSchema() {
        return new WorkflowNodeSchema(
                "tavily_search",
                "Tavily Search",
                "Searches the public web through the configured Tavily integration.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField(
                                "query",
                                "string",
                                true,
                                "{{input.message}}",
                                "Search query template. Supports workflow variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "searchDepth",
                                "string",
                                false,
                                "basic",
                                "Tavily search depth.",
                                orderedMap("allowedValues", List.of("basic", "advanced", "fast", "ultra-fast"))),
                        new WorkflowNodeConfigField(
                                "topic",
                                "string",
                                false,
                                "general",
                                "Search topic category.",
                                orderedMap("allowedValues", List.of("general", "news", "finance"))),
                        new WorkflowNodeConfigField(
                                "maxResults",
                                "integer",
                                false,
                                5,
                                "Maximum number of search results.",
                                orderedMap("min", 1, "max", 20)),
                        new WorkflowNodeConfigField(
                                "includeAnswer",
                                "boolean",
                                false,
                                false,
                                "Ask Tavily to include a synthesized answer.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "includeRawContent",
                                "boolean",
                                false,
                                false,
                                "Include raw page content in each result.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "timeRange",
                                "string",
                                false,
                                null,
                                "Optional recency window.",
                                orderedMap("allowedValues", List.of("day", "week", "month", "year"))),
                        new WorkflowNodeConfigField(
                                "includeDomains",
                                "array",
                                false,
                                List.of(),
                                "Optional domain allowlist.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "excludeDomains",
                                "array",
                                false,
                                List.of(),
                                "Optional domain denylist.",
                                Map.of()))),
                TEMPLATE_VARIABLES,
                "A map containing query, answer, results, responseTime, requestId and optional usage.");
    }

    private WorkflowNodeSchema toolSchema() {
        return new WorkflowNodeSchema(
                "tool",
                "Tool",
                "Calls ToolGatewayService. Supports local tools and allowed MCP remote tools.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField(
                                "toolName",
                                "string",
                                false,
                                null,
                                "Tool name registered in /api/tools.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "arguments",
                                "object",
                                false,
                                Map.of(),
                                "Tool argument object. String values can use workflow variables. Exact variable templates preserve the resolved value type.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "expression",
                                "string",
                                false,
                                null,
                                "Compatibility shortcut for calculate when arguments.expression is absent.",
                                Map.of("onlyForTool", "calculate")),
                        new WorkflowNodeConfigField(
                                "idempotent",
                                "boolean",
                                false,
                                false,
                                "Whether retryCount may be used for this tool node.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "continueOnError",
                                "boolean",
                                false,
                                false,
                                "Continue downstream execution after a failed tool call and expose structured failure status.",
                                Map.of()))),
                TEMPLATE_VARIABLES,
                "A ToolExecutionLog for a successful call, or structured failure status when continuation is enabled.");
    }

    private WorkflowNodeSchema httpRequestSchema() {
        return new WorkflowNodeSchema(
                "http_request",
                "HTTP Request",
                "Calls an external HTTP(S) API using server-side SSRF protection and owner-scoped credentials.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField(
                                "method",
                                "string",
                                false,
                                "GET",
                                "HTTP method.",
                                orderedMap("allowedValues", List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"))),
                        new WorkflowNodeConfigField(
                                "url",
                                "string",
                                true,
                                null,
                                "HTTP(S) endpoint template.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "headers",
                                "array",
                                false,
                                List.of(),
                                "Request header rows with key, value and enabled fields.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "params",
                                "array",
                                false,
                                List.of(),
                                "Query parameter rows with key, value and enabled fields.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "authorization",
                                "object",
                                false,
                                Map.of("type", "none"),
                                "No authentication or an owner-scoped credential reference.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "body",
                                "object",
                                false,
                                Map.of("type", "none", "value", ""),
                                "Request body descriptor. Supports none, json, raw, x-www-form-urlencoded and text-only form-data.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "idempotent",
                                "boolean",
                                false,
                                false,
                                "Allows retryCount for non-GET/HEAD methods when the caller guarantees idempotency.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "continueOnError",
                                "boolean",
                                false,
                                false,
                                "Continue with a structured transport-error output instead of failing the workflow.",
                                Map.of()))),
                TEMPLATE_VARIABLES,
                "A map containing statusCode, headers, body, json, durationMs and succeeded.");
    }

    private WorkflowNodeSchema reportExportSchema() {
        return new WorkflowNodeSchema(
                "report_export",
                "Report Export",
                "Renders an upstream Markdown report into downloadable files and a print preview.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField(
                                "content", "string", true, null,
                                "Markdown report content. Select a reachable upstream string output.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "formats", "array", false, List.of("pdf"),
                                "One or more downloadable report formats.",
                                orderedMap("allowedValues", List.of("pdf", "docx", "html", "markdown", "txt"))),
                        new WorkflowNodeConfigField(
                                "fileName", "string", false, "report",
                                "Base file name without an extension. Supports workflow variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "title", "string", false, "",
                                "Optional report title. Supports workflow variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "author", "string", false, "",
                                "Optional report author. Supports workflow variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "organization", "string", false, "",
                                "Optional organization. Supports workflow variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "theme", "string", false, "business", "Preset report theme.",
                                orderedMap("allowedValues", List.of("business", "minimal", "academic"))),
                        new WorkflowNodeConfigField(
                                "paperSize", "string", false, "A4", "PDF and print paper size.",
                                orderedMap("allowedValues", List.of("A4", "Letter"))),
                        new WorkflowNodeConfigField(
                                "orientation", "string", false, "portrait", "Page orientation.",
                                orderedMap("allowedValues", List.of("portrait", "landscape"))),
                        new WorkflowNodeConfigField(
                                "includeToc", "boolean", false, true, "Include a heading table of contents.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "includePageNumbers", "boolean", false, true, "Include PDF and print page numbers.",
                                Map.of()),
                        new WorkflowNodeConfigField(
                                "retentionDays", "integer", false, 30, "Artifact retention period in days.",
                                orderedMap("min", 1, "max", 365)))),
                TEMPLATE_VARIABLES,
                "A map containing exportId, artifacts, primary, printPreview and expiresAt metadata.");
    }

    private WorkflowNodeSchema customSchema() {
        return new WorkflowNodeSchema(
                "custom",
                "Custom",
                "Safe AI or deterministic template transformation over named inputs; no code or network access.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField(
                                "mode", "string", false, "ai", "Execution mode.",
                                orderedMap("allowedValues", List.of("ai", "template"))),
                        new WorkflowNodeConfigField(
                                "inputs", "object", false, Map.of(),
                                "Named fixed values or reachable upstream references.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "instruction", "string", false, "",
                                "AI business instruction.", Map.of()),
                        new WorkflowNodeConfigField(
                                "template", "any", false, "",
                                "Deterministic template value; nested values support variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "model", "string", false, null,
                                "Optional AI model.", Map.of()),
                        new WorkflowNodeConfigField(
                                "outputMode", "string", false, "text",
                                "AI output is text or JSON.",
                                orderedMap("allowedValues", List.of("text", "json"))),
                        new WorkflowNodeConfigField(
                                "outputSchema", "object", false, Map.of(),
                                "Optional structured output schema.", Map.of()))),
                TEMPLATE_VARIABLES,
                "mode, inputs and output; AI mode also exposes answer, parsed, model and tokenUsage.");
    }

    private WorkflowNodeSchema conditionSchema() {
        return new WorkflowNodeSchema(
                "condition",
                "Condition",
                "Evaluates a boolean expression and routes to condition=true or condition=false outgoing edge.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField(
                                "left",
                                "string",
                                false,
                                "{{input}}",
                                "Left value template. Supports workflow variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "operator",
                                "string",
                                false,
                                "contains",
                                "Comparison operator.",
                                orderedMap("allowedValues", List.of("equals", "notEquals", "contains", "notContains",
                                        "startsWith", "endsWith", "exists", "notExists", "greaterThan",
                                        "lessThan"))),
                        new WorkflowNodeConfigField(
                                "right",
                                "any",
                                false,
                                "",
                                "Right value. String values can use workflow variables. Exact variable templates preserve the resolved value type.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "mode",
                                "string",
                                false,
                                "all",
                                "Composite condition mode used when conditions is configured.",
                                orderedMap("allowedValues", List.of("all", "any"))),
                        new WorkflowNodeConfigField(
                                "conditions",
                                "array",
                                false,
                                List.of(),
                                "Optional list of condition objects with left/operator/right/caseSensitive fields.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "caseSensitive",
                                "boolean",
                                false,
                                false,
                                "Whether string comparison should be case-sensitive.",
                                Map.of()))),
                TEMPLATE_VARIABLES,
                "A map containing left, operator, right, caseSensitive and result.");
    }

    private WorkflowNodeSchema parallelSchema() {
        return new WorkflowNodeSchema(
                "parallel",
                "Parallel",
                "Starts two or more independent linear branches. Branches must converge on the same join node.",
                withExecutionControls(List.of()),
                List.of(),
                "A map indicating branch execution is ready.");
    }

    private WorkflowNodeSchema joinSchema() {
        return new WorkflowNodeSchema(
                "join",
                "Join",
                "Merges outputs from a preceding parallel block.",
                withExecutionControls(List.of()),
                List.of(),
                "A map containing branchOutputs keyed by branch start node id.");
    }

    private WorkflowNodeSchema variableAggregatorSchema() {
        return new WorkflowNodeSchema(
                "variable_aggregator",
                "Variable Aggregator",
                "Selects the first available upstream variable, optionally for multiple named groups.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField(
                                "mode",
                                "string",
                                false,
                                "single",
                                "Single output or multiple named output groups.",
                                orderedMap("allowedValues", List.of("single", "groups"))),
                        new WorkflowNodeConfigField(
                                "outputType",
                                "string",
                                false,
                                "string",
                                "Expected output type in single mode.",
                                orderedMap("allowedValues", List.of("string", "number", "boolean", "object", "array"))),
                        new WorkflowNodeConfigField(
                                "variables",
                                "array",
                                false,
                                List.of(),
                                "Ordered exact variable templates used in single mode.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)),
                        new WorkflowNodeConfigField(
                                "groups",
                                "array",
                                false,
                                List.of(),
                                "Named aggregation groups with key, label, outputType and ordered variables.",
                                Map.of("templateVariables", TEMPLATE_VARIABLES)))),
                TEMPLATE_VARIABLES,
                "A map containing the first available output, or named group outputs in groups mode.");
    }

    private WorkflowNodeSchema loopSchema() {
        return new WorkflowNodeSchema(
                "loop",
                "Loop",
                "Controlled while-loop with maxIterations and condition fields.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField("maxIterations", "integer", false, 10,
                                "Maximum loop iterations (1-50).", orderedMap("min", 1, "max", 50)),
                        new WorkflowNodeConfigField("left", "string", false, "{{input}}",
                                "Condition left operand.", Map.of()),
                        new WorkflowNodeConfigField("operator", "string", false, "greaterthan",
                                "Condition operator.", Map.of()),
                        new WorkflowNodeConfigField("right", "string", false, "0",
                                "Condition right operand.", Map.of()))),
                TEMPLATE_VARIABLES,
                "Loop summary with iterations and iterationOutputs.");
    }

    private WorkflowNodeSchema loopBackSchema() {
        return new WorkflowNodeSchema(
                "loop_back",
                "Loop Back",
                "Compile-time marker ending a loop body.",
                withExecutionControls(List.of()),
                List.of(),
                "Loop back marker output.");
    }

    private WorkflowNodeSchema subgraphSchema() {
        return new WorkflowNodeSchema(
                "subgraph",
                "Subgraph",
                "Runs a saved workflow definition inline.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField("definitionId", "string", true, null,
                                "Saved workflow definition id.", Map.of()),
                        new WorkflowNodeConfigField("version", "integer", false, null,
                                "Optional published revision version.", Map.of()))),
                TEMPLATE_VARIABLES,
                "Nested workflow output summary.");
    }

    private WorkflowNodeSchema dynamicSchema() {
        return new WorkflowNodeSchema(
                "dynamic",
                "Dynamic",
                "Expands a template-resolved list and executes tools sequentially.",
                withExecutionControls(List.of(
                        new WorkflowNodeConfigField("itemsFrom", "string", true, null,
                                "Template resolving to a list of tool names or maps.", Map.of()),
                        new WorkflowNodeConfigField("action", "string", false, "tool",
                                "Dynamic action type.", Map.of()),
                        new WorkflowNodeConfigField("allowedTools", "array", true, List.of(),
                                "Explicit tool allowlist for dynamic execution.", Map.of()))),
                TEMPLATE_VARIABLES,
                "Dynamic execution outputs list.");
    }

    private WorkflowNodeSchema endSchema() {
        return new WorkflowNodeSchema(
                "end",
                "End",
                "Workflow terminal node. It returns the final answer when an LLM node ran, otherwise lastOutput.",
                withExecutionControls(List.of()),
                List.of(),
                "The final workflow output.");
    }

    private List<WorkflowNodeConfigField> withExecutionControls(List<WorkflowNodeConfigField> fields) {
        List<WorkflowNodeConfigField> merged = new ArrayList<>(fields);
        merged.add(new WorkflowNodeConfigField(
                "writeState",
                "object",
                false,
                Map.of(),
                "Optional map of workflow state variables to write after this node succeeds. Values support workflow variables.",
                Map.of("templateVariables", TEMPLATE_VARIABLES)));
        merged.add(new WorkflowNodeConfigField(
                "retryCount",
                "integer",
                false,
                0,
                "Maximum retry count after the first failed attempt.",
                orderedMap("min", 0, "max", 5)));
        merged.add(new WorkflowNodeConfigField(
                "timeoutMs",
                "integer",
                false,
                0,
                "Per-attempt timeout in milliseconds. 0 disables timeout.",
                orderedMap("min", 0, "max", 300000)));
        return List.copyOf(merged);
    }

    private Map<String, Object> orderedMap(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length - 1; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        return map;
    }

}
