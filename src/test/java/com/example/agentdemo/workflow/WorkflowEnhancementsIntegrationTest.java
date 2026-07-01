package com.example.agentdemo.workflow;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.trace.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-2 workflow enhancements: layout + variable-schema persistence round-trip, node-palette groups,
 * run-events snapshot (node events + run_done), and cancel semantics for a finished run.
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "demo.workflow.runtime=simple",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_wf_enh_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
class WorkflowEnhancementsIntegrationTest {

    @Autowired
    private WorkflowDefinitionService workflowDefinitionService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowNodeSchemaRegistry workflowNodeSchemaRegistry;

    @Autowired
    private WorkflowRunEventService workflowRunEventService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void layoutAndVariablesPersistAndRoundTrip() throws Exception {
        var layout = objectMapper.readTree("{\"nodes\":{\"start\":{\"x\":10,\"y\":20}}}");
        WorkflowVariableSchema variables = new WorkflowVariableSchema(
                List.of(new WorkflowVariableSchema.InputVariable("message", "string", true, "hi", "the input")),
                List.of(new WorkflowVariableSchema.OutputVariable("answer", "answer", "final answer")));
        WorkflowDefinitionSaveRequest request = new WorkflowDefinitionSaveRequest("Flow", "desc",
                simpleWorkflow(), layout, variables);

        WorkflowDefinitionResponse saved = workflowDefinitionService.save(request);
        WorkflowDefinitionResponse fetched = workflowDefinitionService.get(saved.definitionId());

        assertThat(fetched.layout()).isNotNull();
        assertThat(fetched.layout().path("nodes").path("start").path("x").asInt()).isEqualTo(10);
        assertThat(fetched.variables()).isNotNull();
        assertThat(fetched.variables().inputs()).hasSize(1);
        assertThat(fetched.variables().inputs().get(0).name()).isEqualTo("message");
        assertThat(fetched.variables().inputs().get(0).required()).isTrue();
        assertThat(fetched.variables().outputs().get(0).path()).isEqualTo("answer");
    }

    @Test
    void nodeSchemasCarryPaletteGroups() {
        List<WorkflowNodeSchema> schemas = workflowNodeSchemaRegistry.listSchemas();
        assertThat(group(schemas, "start")).isEqualTo("Basic");
        assertThat(group(schemas, "llm")).isEqualTo("LLM");
        assertThat(group(schemas, "retriever")).isEqualTo("Knowledge");
        assertThat(group(schemas, "tool")).isEqualTo("Tools");
        assertThat(group(schemas, "condition")).isEqualTo("Flow Control");
        assertThat(group(schemas, "subgraph")).isEqualTo("Advanced");
    }

    @Test
    void runEventsSnapshotIncludesRunDone() {
        WorkflowDefinitionResponse saved = workflowDefinitionService.save(
                new WorkflowDefinitionSaveRequest("Flow", null, simpleWorkflow()));
        workflowDefinitionService.publish(saved.definitionId());
        WorkflowRunResponse run = workflowService.run(new WorkflowRunRequest(null, saved.definitionId(), null,
                Map.of("message", "hi")));

        WorkflowRunEventsSnapshot snapshot = workflowRunEventService.snapshot(run.runId());

        assertThat(snapshot.terminal()).isTrue();
        assertThat(snapshot.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(snapshot.events()).extracting(WorkflowRunEvent::event).contains("run_done");

        // Cancelling a finished run is a no-op that reports its terminal status.
        WorkflowRunCancelResponse cancel = workflowService.cancelRun(run.runId());
        assertThat(cancel.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(cancel.requested()).isFalse();
    }

    private String group(List<WorkflowNodeSchema> schemas, String type) {
        return schemas.stream().filter(s -> s.type().equals(type)).findFirst().orElseThrow().group();
    }

    private WorkflowDefinition simpleWorkflow() {
        return new WorkflowDefinition(
                List.of(new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
    }

}
