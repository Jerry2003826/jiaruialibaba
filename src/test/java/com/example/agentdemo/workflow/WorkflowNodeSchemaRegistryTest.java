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
                .containsExactly("start", "retriever", "llm", "tool", "condition", "end");
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
