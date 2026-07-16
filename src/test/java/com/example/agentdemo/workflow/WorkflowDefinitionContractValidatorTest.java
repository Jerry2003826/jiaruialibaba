package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

class WorkflowDefinitionContractValidatorTest {

    private final WorkflowDefinitionContractValidator validator = new WorkflowDefinitionContractValidator();

    @Test
    void acceptsCompleteOutputReferenceForAnExistingNode() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_search", "tavily_search", Map.of("query", "{{input.message}}")),
                        new WorkflowNode("llm_report", "llm", Map.of(
                                "prompt", "Summarize these search results: {{nodes.tool_search}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_search"),
                        new WorkflowEdge("tool_search", "llm_report"),
                        new WorkflowEdge("llm_report", "end")));

        assertThatCode(() -> validator.validate(definition)).doesNotThrowAnyException();
    }
}
