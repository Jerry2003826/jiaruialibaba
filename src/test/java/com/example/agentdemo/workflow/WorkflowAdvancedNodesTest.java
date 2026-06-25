package com.example.agentdemo.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowAdvancedNodesTest {

    private final WorkflowCompiler compiler = new WorkflowCompiler(new WorkflowNodeSchemaRegistry());

    @Test
    void compilesLoopWorkflowWithLoopBackCycle() {
        WorkflowExecutionPlan plan = compiler.compile(loopDefinition());

        assertThat(plan.loopBlocks()).hasSize(1);
        assertThat(plan.loopBlocks().getFirst().bodyNodeIds()).containsExactly("decrement");
        assertThat(plan.isCompositeScopedNode("decrement")).isTrue();
        assertThat(plan.isCompositeScopedNode("loop_back")).isTrue();
    }

    @Test
    void compilesDynamicAndSubgraphNodeTypes() {
        WorkflowExecutionPlan plan = compiler.compile(new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("sub_1", "subgraph", Map.of("definitionId", "wf-child")),
                        new WorkflowNode("dyn_1", "dynamic", Map.of("itemsFrom", "{{input.tools}}")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "sub_1"),
                        new WorkflowEdge("sub_1", "dyn_1"),
                        new WorkflowEdge("dyn_1", "end"))));

        assertThat(plan.nodesById()).containsKeys("sub_1", "dyn_1");
    }

    private WorkflowDefinition loopDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("loop_1", "loop", Map.of(
                                "maxIterations", 5,
                                "left", "{{input.count}}",
                                "operator", "greaterthan",
                                "right", "0")),
                        new WorkflowNode("decrement", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("loop_back", "loop_back", Map.of()),
                        new WorkflowNode("after_loop", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "loop_1"),
                        new WorkflowEdge("loop_1", "decrement", "body"),
                        new WorkflowEdge("loop_1", "after_loop", "exit"),
                        new WorkflowEdge("decrement", "loop_back"),
                        new WorkflowEdge("loop_back", "loop_1"),
                        new WorkflowEdge("after_loop", "end")));
    }

}
