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
                .containsExactly("start", "retriever", "llm", "tool", "condition", "parallel", "join", "loop",
                        "loop_back", "subgraph", "dynamic", "end");
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
                        "{{lastOutput.field}}", "{{nodes.nodeId.field}}", "{{toolResult}}", "{{answer}}");
        assertThat(field(llm, "prompt").defaultValue()).asString().contains("{{context}}");
    }

    @Test
    void exposesToolArgumentsAsObjectConfig() {
        WorkflowNodeSchema tool = schema("tool");

        assertThat(field(tool, "toolName").defaultValue()).isEqualTo("getCurrentTime");
        assertThat(field(tool, "arguments").type()).isEqualTo("object");
        assertThat(field(tool, "expression").constraints()).containsEntry("onlyForTool", "calculate");
    }

    @Test
    void exposesConditionOperatorConstraints() {
        WorkflowNodeSchema condition = schema("condition");

        assertThat(field(condition, "left").defaultValue()).isEqualTo("{{input}}");
        assertThat(field(condition, "operator").constraints())
                .containsKey("allowedValues");
        assertThat(field(condition, "caseSensitive").defaultValue()).isEqualTo(false);
    }

    @Test
    void exposesParallelAndJoinSchemas() {
        assertThat(schema("parallel").outputDescription()).contains("branch");
        assertThat(schema("join").outputDescription()).contains("branchOutputs");
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
        assertThat(field(dynamic, "action").defaultValue()).isEqualTo("tool");

        assertThat(schema("loop_back").configFields())
                .extracting(WorkflowNodeConfigField::name)
                .containsExactly("retryCount", "timeoutMs");
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
