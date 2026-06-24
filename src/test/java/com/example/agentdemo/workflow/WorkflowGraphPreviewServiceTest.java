package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowGraphPreviewServiceTest {

    private final WorkflowGraphPreviewService service = new WorkflowGraphPreviewService(
            new WorkflowCompiler(new WorkflowNodeSchemaRegistry()));

    @Test
    void previewsValidLinearWorkflow() {
        WorkflowGraphPreviewResponse response = service.preview(new WorkflowGraphPreviewRequest(linearDefinition()));

        assertThat(response.valid()).isTrue();
        assertThat(response.errors()).isEmpty();
        assertThat(response.summary())
                .isEqualTo(new WorkflowValidationSummary(3, 2, true, "start", "end",
                        List.of("start", "llm", "end")));
        assertThat(response.nodes())
                .extracting(WorkflowGraphNodeView::id)
                .containsExactly("start", "llm_1", "end");
        assertThat(response.edges())
                .containsExactly(
                        new WorkflowGraphEdgeView("start", "llm_1", null, null),
                        new WorkflowGraphEdgeView("llm_1", "end", null, null));
        assertThat(response.mermaid())
                .contains("flowchart TD")
                .contains("n0[\"start (start)\"]")
                .contains("n1[\"llm_1 (llm)\"]")
                .contains("n0 --> n1")
                .contains("n1 --> n2");
    }

    @Test
    void previewsConditionalWorkflowWithEdgeLabels() {
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("check", "condition", Map.of("left", "{{input.message}}")),
                        new WorkflowNode("llm_true", "llm", Map.of("prompt", "true path")),
                        new WorkflowNode("llm_false", "llm", Map.of("prompt", "false path")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "check"),
                        new WorkflowEdge("check", "llm_true", "true"),
                        new WorkflowEdge("check", "llm_false", "false"),
                        new WorkflowEdge("llm_true", "end"),
                        new WorkflowEdge("llm_false", "end")));

        WorkflowGraphPreviewResponse response = service.preview(new WorkflowGraphPreviewRequest(definition));

        assertThat(response.valid()).isTrue();
        assertThat(response.summary().linear()).isFalse();
        assertThat(response.edges())
                .contains(
                        new WorkflowGraphEdgeView("check", "llm_true", "true", "true"),
                        new WorkflowGraphEdgeView("check", "llm_false", "false", "false"));
        assertThat(response.mermaid())
                .contains("n1 -- \"true\" --> n2")
                .contains("n1 -- \"false\" --> n3");
    }

    @Test
    void escapesMermaidLabelsAndUsesStableAliases() {
        String unsafeNodeId = "llm \"quoted\" [A]\nnode";
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode(unsafeNodeId, "llm", Map.of("prompt", "Answer: {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", unsafeNodeId),
                        new WorkflowEdge(unsafeNodeId, "end")));

        WorkflowGraphPreviewResponse response = service.preview(new WorkflowGraphPreviewRequest(definition));

        assertThat(response.valid()).isTrue();
        assertThat(response.mermaid())
                .contains("n1[\"llm \\\"quoted\\\" &#91;A&#93; node (llm)\"]")
                .contains("n0 --> n1")
                .contains("n1 --> n2")
                .doesNotContain(unsafeNodeId + " -->");
    }

    @Test
    void returnsValidationErrorForInvalidWorkflow() {
        WorkflowDefinition invalidDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool", "tool", Map.of("unexpected", true)),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool"),
                        new WorkflowEdge("tool", "end")));

        WorkflowGraphPreviewResponse response = service.preview(new WorkflowGraphPreviewRequest(invalidDefinition));

        assertThat(response.valid()).isFalse();
        assertThat(response.summary()).isNull();
        assertThat(response.nodes()).isEmpty();
        assertThat(response.edges()).isEmpty();
        assertThat(response.mermaid()).isEmpty();
        assertThat(response.errors())
                .containsExactly(new WorkflowValidationError("WORKFLOW_VALIDATION_FAILED",
                        "Unsupported config key for node tool: unexpected"));
    }

    private WorkflowDefinition linearDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("llm_1", "llm", Map.of("prompt", "Answer: {{input}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "llm_1"),
                        new WorkflowEdge("llm_1", "end")));
    }

}
