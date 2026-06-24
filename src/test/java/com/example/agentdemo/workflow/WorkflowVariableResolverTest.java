package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowVariableResolverTest {

    private final WorkflowVariableResolver resolver = new WorkflowVariableResolver();

    @Test
    void rendersCanonicalWorkflowVariablesAndDottedPaths() {
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of(
                "message", "hello",
                "meta", Map.of("intent", "time"),
                "items", List.of("first", "second")));
        state.setLastOutput(Map.of(
                "answer", "ok",
                "scores", List.of(10, 20)));
        state.recordNodeOutput("llm_1");

        String rendered = resolver.renderString(
                "{{input}}|{{input.meta.intent}}|{{input.items.1}}|{{lastOutput.answer}}|{{nodes.llm_1.scores.0}}",
                state);

        assertThat(rendered).isEqualTo("hello|time|second|ok|10");
    }

    @Test
    void preservesValueTypeWhenTemplateIsOnlyAVariable() {
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello", "count", 7));

        Object exactValue = resolver.renderValue("{{input.count}}", state);
        Object interpolatedValue = resolver.renderValue("count={{input.count}}", state);

        assertThat(exactValue).isEqualTo(7);
        assertThat(interpolatedValue).isEqualTo("count=7");
    }

    @Test
    void returnsEmptyStringForMissingExactVariableValue() {
        WorkflowExecutionState state = new WorkflowExecutionState(Map.of("message", "hello"));

        assertThat(resolver.renderValue("{{input.missing}}", state)).isEqualTo("");
        assertThat(resolver.renderString("x{{input.missing}}y", state)).isEqualTo("xy");
    }

}
