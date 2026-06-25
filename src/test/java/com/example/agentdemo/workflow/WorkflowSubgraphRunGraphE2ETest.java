package com.example.agentdemo.workflow;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.trace.TraceService;
import com.example.agentdemo.trace.dto.RunStepResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.active=",
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "demo.rag.retriever=keyword",
        "demo.workflow.runtime=graph",
        "demo.workflow.require-published-for-run=false",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:subgraph_run_graph_e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key="
})
class WorkflowSubgraphRunGraphE2ETest {

    @Autowired
    private WorkflowDefinitionService workflowDefinitionService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowRunGraphService workflowRunGraphService;

    @Autowired
    private TraceService traceService;

    @Test
    void runsSubgraphOnParentRunIdAndExposesNamespacedStepsInRunGraph() {
        WorkflowDefinition childDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("tool_1", "tool", Map.of("toolName", "getCurrentTime")),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "tool_1"),
                        new WorkflowEdge("tool_1", "end")));
        WorkflowDefinitionResponse child = workflowDefinitionService.save(new WorkflowDefinitionSaveRequest(
                "child-subgraph-run-graph", "child", childDefinition));

        WorkflowDefinition parentDefinition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("sub_1", "subgraph", Map.of("definitionId", child.definitionId())),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(
                        new WorkflowEdge("start", "sub_1"),
                        new WorkflowEdge("sub_1", "end")));
        WorkflowDefinitionResponse parent = workflowDefinitionService.save(new WorkflowDefinitionSaveRequest(
                "parent-subgraph-run-graph", "parent", parentDefinition));

        WorkflowRunResponse run = workflowService.run(new WorkflowRunRequest(null, parent.definitionId(), Map.of()));

        List<RunStepResponse> steps = traceService.listSteps(run.runId());
        assertThat(steps)
                .extracting(RunStepResponse::nodeName)
                .contains("workflow_node_sub_1::tool_1");

        WorkflowRunGraphResponse graph = workflowRunGraphService.getRunGraph(run.runId());
        WorkflowRunGraphNodeView subgraphNode = graph.nodes().stream()
                .filter(node -> "sub_1".equals(node.id()))
                .findFirst()
                .orElseThrow();
        assertThat(subgraphNode.compositeRole()).isEqualTo("SUBGRAPH");
        assertThat(subgraphNode.children())
                .extracting(step -> step.id())
                .contains("sub_1::tool_1");
    }

}
