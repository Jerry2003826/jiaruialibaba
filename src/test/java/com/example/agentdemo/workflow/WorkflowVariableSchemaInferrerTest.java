package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowVariableSchemaInferrerTest {

    @Test
    void infersSearchTopicFromTavilyQuery() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("search", "tavily_search", Map.of(
                                "query", "{{input.message}}",
                                "topic", "general")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "search"),
                        new WorkflowEdge("search", "end")));

        WorkflowVariableSchema schema = WorkflowVariableSchemaInferrer.infer(definition, null);

        assertThat(schema.inputs()).containsExactly(
                new WorkflowVariableSchema.InputVariable(
                        "message", "string", true, null, "输入要研究或搜索的主题"));
    }

    @Test
    void preservesDeclaredMetadataAndAddsReferencedInputs() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm", "llm", Map.of(
                                "prompt", "分析 {{input.customer.name}}，最多返回 {{input.count}} 条")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "llm"),
                        new WorkflowEdge("llm", "end")));
        WorkflowVariableSchema declared = new WorkflowVariableSchema(
                List.of(new WorkflowVariableSchema.InputVariable(
                        "count", "number", false, 5, "结果数量")),
                List.of(new WorkflowVariableSchema.OutputVariable("answer", "answer", "研究报告")));

        WorkflowVariableSchema schema = WorkflowVariableSchemaInferrer.infer(definition, declared);

        assertThat(schema.inputs()).extracting(WorkflowVariableSchema.InputVariable::name)
                .containsExactly("count", "customer");
        assertThat(schema.inputs().getFirst()).isEqualTo(declared.inputs().getFirst());
        assertThat(schema.inputs().get(1).type()).isEqualTo("object");
        assertThat(schema.outputs()).isEqualTo(declared.outputs());
    }
}
