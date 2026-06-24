package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowCompilerTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());

    @Test
    void compilesValidLinearWorkflow() {
        List<WorkflowNode> nodes = compiler.compile(definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("retriever", "retriever", Map.of("topK", 3)),
                new WorkflowNode("llm", "llm", Map.of("prompt", "Answer: {{context}}")),
                new WorkflowNode("end", "end", Map.of())));

        assertThat(nodes)
                .extracting(WorkflowNode::id)
                .containsExactly("start", "retriever", "llm", "end");
    }

    @Test
    void rejectsUnknownConfigKeys() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of("unexpected", true)),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported config key for node start: unexpected");
    }

    @Test
    void rejectsRetrieverTopKOutsideSchemaRange() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("retriever", "retriever", Map.of("topK", 21)),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config retriever.topK must be <= 20");
    }

    @Test
    void rejectsRetrieverTopKWithWrongType() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("retriever", "retriever", Map.of("topK", "three")),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config retriever.topK must be integer");
    }

    @Test
    void rejectsBlankToolNameWhenConfigured() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of("toolName", " ")),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config tool.toolName must not be blank");
    }

    @Test
    void rejectsToolArgumentsWithWrongType() {
        WorkflowDefinition definition = definition(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("tool", "tool", Map.of("arguments", "not-an-object")),
                new WorkflowNode("end", "end", Map.of()));

        assertThatThrownBy(() -> compiler.compile(definition))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Config tool.arguments must be object");
    }

    private WorkflowDefinition definition(WorkflowNode... nodes) {
        return new WorkflowDefinition(List.of(nodes), edgesFor(nodes));
    }

    private List<WorkflowEdge> edgesFor(WorkflowNode[] nodes) {
        java.util.ArrayList<WorkflowEdge> edges = new java.util.ArrayList<>();
        for (int i = 0; i < nodes.length - 1; i++) {
            edges.add(new WorkflowEdge(nodes[i].id(), nodes[i + 1].id()));
        }
        return edges;
    }

}
