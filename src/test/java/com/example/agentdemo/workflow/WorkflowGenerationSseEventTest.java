package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.workflow.governance.WorkflowBuilderContext;
import com.example.agentdemo.workflow.governance.WorkflowEvaluationReport;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowGenerationSseEventTest {

    @Test
    void streamingProgressCarriesMachineReadablePhaseAndStatusThroughReadyCompletion() {
        AiModelService aiModelService = mock(AiModelService.class);
        WorkflowGovernanceOrchestrator orchestrator = mock(WorkflowGovernanceOrchestrator.class);
        WorkflowDefinition definition = new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
        WorkflowGovernanceEvaluationResponse governed = new WorkflowGovernanceEvaluationResponse(
                WorkflowGenerationStatus.READY,
                definition,
                new WorkflowGovernanceReport(List.of()),
                new WorkflowEvaluationReport(Map.of("message", "hello"), List.of()),
                List.of(),
                List.of(new WorkflowActiveRulePack("core", "1.0.0")));
        when(aiModelService.modelName()).thenReturn("qwen3.7-max");
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onChunk = invocation.getArgument(2);
            onChunk.accept("""
                    {
                      "name":"Generated",
                      "description":"generated",
                      "workflowDefinition":{
                        "nodes":[
                          {"id":"start","type":"start","config":{}},
                          {"id":"end","type":"end","config":{}}
                        ],
                        "edges":[{"from":"start","to":"end"}]
                      },
                      "testInput":{"message":"hello"},
                      "notes":[]
                    }
                    """);
            return null;
        }).when(aiModelService).streamUntilComplete(anyString(), anyString(), any(), any());
        when(orchestrator.evaluate(any(WorkflowDefinition.class), nullable(WorkflowBuilderContext.class),
                anyMap(), anyList())).thenReturn(governed);
        WorkflowGenerationService service = new WorkflowGenerationService(
                aiModelService,
                new ObjectMapper(),
                new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                new WorkflowStructuredOutputAutoconfigurer(),
                null,
                null,
                null,
                null,
                null,
                null,
                orchestrator);
        List<Map<String, Object>> statusEvents = new ArrayList<>();

        WorkflowGenerationResponse response = service.generateStreaming(
                new WorkflowGenerationRequest("generate a simple workflow"),
                (event, data) -> {
                    if ("status".equals(event)) {
                        statusEvents.add(data);
                    }
                });

        assertThat(response.status()).isEqualTo(WorkflowGenerationStatus.READY);
        assertThat(response.activeRulePacks())
                .extracting(WorkflowActiveRulePack::id, WorkflowActiveRulePack::version)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("core", "1.0.0"));
        assertThat(statusEvents).isNotEmpty().allSatisfy(event -> {
            assertThat(event).containsKeys("phase", "status", "message");
            assertThat(event.get("phase")).isInstanceOf(String.class);
            assertThat(event.get("status")).isInstanceOf(String.class);
        });
        assertThat(statusEvents.getLast())
                .containsEntry("phase", "COMPLETE")
                .containsEntry("status", "READY");
    }
}
