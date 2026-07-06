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
            "{{nodes.nodeId.field}}",
            "{{toolResult}}",
            "{{answer}}");

    /** Palette group per node type (Basic / LLM / Knowledge / Tools / Flow Control / Advanced). */
    private static final Map<String, String> NODE_GROUPS = Map.ofEntries(
            Map.entry("start", "Basic"),
            Map.entry("end", "Basic"),
            Map.entry("llm", "LLM"),
            Map.entry("retriever", "Knowledge"),
            Map.entry("tool", "Tools"),
            Map.entry("dynamic", "Tools"),
            Map.entry("condition", "Flow Control"),
            Map.entry("parallel", "Flow Control"),
            Map.entry("join", "Flow Control"),
            Map.entry("loop", "Flow Control"),
            Map.entry("loop_back", "Flow Control"),
            Map.entry("subgraph", "Advanced"));

    private final List<WorkflowNodeSchema> schemas = List.of(
            startSchema(),
            retrieverSchema(),
            llmSchema(),
            toolSchema(),
            conditionSchema(),
            parallelSchema(),
            joinSchema(),
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
                                Map.of()))),
                TEMPLATE_VARIABLES,
                "A map containing prompt, answer, parsed, model, tokenUsage, fallback and errorMessage.");
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
                                "getCurrentTime",
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
                                Map.of()))),
                TEMPLATE_VARIABLES,
                "A ToolExecutionLog for the tool call.");
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
