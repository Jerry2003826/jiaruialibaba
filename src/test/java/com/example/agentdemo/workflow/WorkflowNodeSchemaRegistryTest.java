package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowNodeSchemaRegistryTest {

    private final WorkflowNodeSchemaRegistry registry = new WorkflowNodeSchemaRegistry();

    @Test
    void listsSchemasForSupportedWorkflowNodeTypes() {
        List<WorkflowNodeSchema> schemas = registry.listSchemas();

        assertThat(schemas)
                .extracting(WorkflowNodeSchema::type)
                .containsExactly("start", "retriever", "tavily_search", "llm", "tool", "http_request",
                        "report_export", "custom", "condition", "parallel", "join", "variable_aggregator", "loop",
                        "loop_back", "subgraph", "dynamic", "end");
    }

    @Test
    void exposesSafeCustomNodeContract() {
        WorkflowNodeSchema custom = schema("custom");

        assertThat(custom.group()).isEqualTo("Tools");
        assertThat(field(custom, "mode").defaultValue()).isEqualTo("ai");
        assertThat(field(custom, "mode").constraints().get("allowedValues").toString())
                .contains("ai", "template");
        assertThat(field(custom, "inputs").type()).isEqualTo("object");
        assertThat(field(custom, "instruction").required()).isFalse();
        assertThat(field(custom, "template").required()).isFalse();
        assertThat(field(custom, "outputMode").constraints().get("allowedValues").toString())
                .contains("text", "json");
        assertThat(custom.outputDescription())
                .contains("inputs", "answer", "parsed", "output");
    }

    @Test
    void exposesReportExportContract() {
        WorkflowNodeSchema report = schema("report_export");

        assertThat(report.group()).isEqualTo("Tools");
        assertThat(field(report, "content").required()).isTrue();
        assertThat(field(report, "formats").defaultValue()).isEqualTo(List.of("pdf"));
        assertThat(field(report, "formats").constraints().get("allowedValues").toString())
                .contains("pdf", "docx", "html", "markdown", "txt");
        assertThat(field(report, "theme").constraints().get("allowedValues").toString())
                .contains("business", "minimal", "academic");
        assertThat(field(report, "paperSize").constraints().get("allowedValues").toString())
                .contains("A4", "Letter");
        assertThat(field(report, "retentionDays").constraints())
                .containsEntry("min", 1)
                .containsEntry("max", 365);
        assertThat(report.outputDescription())
                .contains("artifacts", "primary", "printPreview", "expiresAt");
    }

    @Test
    void exposesDedicatedTavilySearchContractWithoutAnApiKeyField() {
        WorkflowNodeSchema tavily = schema("tavily_search");

        assertThat(tavily.group()).isEqualTo("Knowledge");
        assertThat(field(tavily, "query").required()).isTrue();
        assertThat(field(tavily, "query").defaultValue()).isEqualTo("{{input.message}}");
        assertThat(field(tavily, "searchDepth").constraints().get("allowedValues").toString())
                .contains("basic", "advanced", "fast", "ultra-fast");
        assertThat(field(tavily, "topic").constraints().get("allowedValues").toString())
                .contains("general", "news", "finance");
        assertThat(field(tavily, "maxResults").constraints())
                .containsEntry("min", 1)
                .containsEntry("max", 20);
        assertThat(tavily.configFields())
                .extracting(WorkflowNodeConfigField::name)
                .doesNotContain("apiKey", "token", "authorization");
        assertThat(tavily.outputDescription()).contains("results", "requestId");
    }

    @Test
    void exposesRetrieverTopKConstraintsAndLlmTemplateVariables() {
        WorkflowNodeSchema retriever = schema("retriever");
        WorkflowNodeConfigField topK = field(retriever, "topK");

        assertThat(topK.defaultValue()).isEqualTo(3);
        assertThat(topK.constraints())
                .containsEntry("min", 1)
                .containsEntry("max", 20);

        WorkflowNodeSchema llm = schema("llm");
        assertThat(llm.templateVariables())
                .contains("{{input}}", "{{input.field}}", "{{context}}", "{{lastOutput}}",
                        "{{lastOutput.field}}", "{{state.field}}", "{{nodes.nodeId}}",
                        "{{nodes.nodeId.field}}", "{{toolResult}}", "{{answer}}");
        assertThat(field(llm, "prompt").defaultValue()).asString().contains("{{context}}");
        assertThat(field(llm, "model").defaultValue()).isNull();
    }

    @Test
    void exposesLlmOutputContractFields() {
        WorkflowNodeSchema llm = schema("llm");

        WorkflowNodeConfigField outputMode = field(llm, "outputMode");
        assertThat(outputMode.type()).isEqualTo("string");
        assertThat(outputMode.defaultValue()).isEqualTo("text");
        assertThat(outputMode.constraints().get("allowedValues").toString())
                .contains("text", "json");

        WorkflowNodeConfigField outputSchema = field(llm, "outputSchema");
        assertThat(outputSchema.type()).isEqualTo("object");
        assertThat(outputSchema.required()).isFalse();
        assertThat(outputSchema.defaultValue()).isEqualTo(java.util.Map.of());

        WorkflowNodeConfigField autoStructuredOutputContract = field(llm, "autoStructuredOutputContract");
        assertThat(autoStructuredOutputContract.type()).isEqualTo("string");
        assertThat(autoStructuredOutputContract.required()).isFalse();
        assertThat(autoStructuredOutputContract.defaultValue()).isNull();
    }

    @Test
    void exposesToolArgumentsAsObjectConfig() {
        WorkflowNodeSchema tool = schema("tool");

        assertThat(field(tool, "toolName").required()).isFalse();
        assertThat(field(tool, "toolName").defaultValue()).isNull();
        assertThat(field(tool, "arguments").type()).isEqualTo("object");
        assertThat(field(tool, "expression").constraints()).containsEntry("onlyForTool", "calculate");
        assertThat(field(tool, "idempotent").defaultValue()).isEqualTo(false);
        assertThat(tool.configFields())
                .extracting(WorkflowNodeConfigField::name)
                .contains("continueOnError");
        WorkflowNodeConfigField continueOnError = field(tool, "continueOnError");
        assertThat(continueOnError.type()).isEqualTo("boolean");
        assertThat(continueOnError.required()).isFalse();
        assertThat(continueOnError.defaultValue()).isEqualTo(false);
    }

    @Test
    void exposesConditionOperatorConstraints() {
        WorkflowNodeSchema condition = schema("condition");

        assertThat(field(condition, "left").defaultValue()).isEqualTo("{{input}}");
        assertThat(field(condition, "operator").constraints())
                .containsKey("allowedValues");
        assertThat(field(condition, "operator").constraints().get("allowedValues").toString())
                .contains("greaterThan", "lessThan");
        assertThat(field(condition, "mode").constraints().get("allowedValues").toString())
                .contains("all", "any");
        assertThat(field(condition, "conditions").type()).isEqualTo("array");
        assertThat(field(condition, "caseSensitive").defaultValue()).isEqualTo(false);
    }

    @Test
    void exposesParallelAndJoinSchemas() {
        assertThat(schema("parallel").outputDescription()).contains("branch");
        assertThat(schema("join").outputDescription()).contains("branchOutputs");
    }

    @Test
    void exposesHttpRequestContractWithoutInlineSecretFields() {
        WorkflowNodeSchema http = schema("http_request");

        assertThat(http.group()).isEqualTo("Tools");
        assertThat(field(http, "method").constraints().get("allowedValues").toString())
                .contains("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
        assertThat(field(http, "url").required()).isTrue();
        assertThat(field(http, "headers").type()).isEqualTo("array");
        assertThat(field(http, "params").type()).isEqualTo("array");
        assertThat(field(http, "authorization").type()).isEqualTo("object");
        assertThat(field(http, "body").type()).isEqualTo("object");
        assertThat(field(http, "idempotent").defaultValue()).isEqualTo(false);
        assertThat(field(http, "continueOnError").defaultValue()).isEqualTo(false);
        assertThat(http.configFields())
                .extracting(WorkflowNodeConfigField::name)
                .doesNotContain("apiKey", "token", "password", "secret");
        assertThat(http.outputDescription())
                .contains("statusCode", "headers", "body", "json", "durationMs", "succeeded");
    }

    @Test
    void exposesVariableAggregatorContractSeparatelyFromJoin() {
        WorkflowNodeSchema aggregator = schema("variable_aggregator");

        assertThat(aggregator.group()).isEqualTo("Flow Control");
        assertThat(field(aggregator, "mode").constraints().get("allowedValues").toString())
                .contains("single", "groups");
        assertThat(field(aggregator, "outputType").constraints().get("allowedValues").toString())
                .contains("string", "number", "boolean", "object", "array");
        assertThat(field(aggregator, "variables").type()).isEqualTo("array");
        assertThat(field(aggregator, "groups").type()).isEqualTo("array");
        assertThat(aggregator.outputDescription()).contains("first available");
    }

    @Test
    void exposesAdvancedNodeSchemas() {
        WorkflowNodeSchema loop = schema("loop");
        assertThat(field(loop, "maxIterations").constraints())
                .containsEntry("min", 1)
                .containsEntry("max", 50);
        assertThat(field(loop, "operator").defaultValue()).isEqualTo("greaterthan");

        WorkflowNodeSchema subgraph = schema("subgraph");
        assertThat(field(subgraph, "definitionId").required()).isTrue();

        WorkflowNodeSchema dynamic = schema("dynamic");
        assertThat(field(dynamic, "itemsFrom").required()).isTrue();
        assertThat(field(dynamic, "allowedTools").required()).isTrue();
        assertThat(field(dynamic, "action").defaultValue()).isEqualTo("tool");

        assertThat(schema("loop_back").configFields())
                .extracting(WorkflowNodeConfigField::name)
                .containsExactly("writeState", "retryCount", "timeoutMs");
    }

    @Test
    void exposesExecutionControlsOnEveryNodeSchema() {
        for (WorkflowNodeSchema schema : registry.listSchemas()) {
            assertThat(field(schema, "retryCount").defaultValue()).isEqualTo(0);
            assertThat(field(schema, "retryCount").constraints())
                    .containsEntry("min", 0)
                    .containsEntry("max", 5);
            assertThat(field(schema, "timeoutMs").defaultValue()).isEqualTo(0);
            assertThat(field(schema, "timeoutMs").constraints())
                    .containsEntry("min", 0)
                    .containsEntry("max", 300000);
        }
    }

    @Test
    void exposesWriteStateOnEveryNodeSchema() {
        for (WorkflowNodeSchema schema : registry.listSchemas()) {
            WorkflowNodeConfigField writeState = field(schema, "writeState");
            assertThat(writeState.type()).isEqualTo("object");
            assertThat(writeState.required()).isFalse();
            assertThat(writeState.defaultValue()).isEqualTo(java.util.Map.of());
        }
    }

    private WorkflowNodeSchema schema(String type) {
        return registry.listSchemas().stream()
                .filter(schema -> type.equals(schema.type()))
                .findFirst()
                .orElseThrow();
    }

    private WorkflowNodeConfigField field(WorkflowNodeSchema schema, String name) {
        return schema.configFields().stream()
                .filter(field -> name.equals(field.name()))
                .findFirst()
                .orElseThrow();
    }

}
