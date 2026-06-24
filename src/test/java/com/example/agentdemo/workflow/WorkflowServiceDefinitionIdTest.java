package com.example.agentdemo.workflow;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.trace.RunEntity;
import com.example.agentdemo.trace.RunStatus;
import com.example.agentdemo.trace.RunType;
import com.example.agentdemo.trace.TraceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowServiceDefinitionIdTest {

    @Test
    void runsWorkflowByPersistedDefinitionId() {
        WorkflowDefinition definition = validDefinition();
        WorkflowDefinitionService definitionService = mock(WorkflowDefinitionService.class);
        when(definitionService.resolveDefinition("wf-1")).thenReturn(definition);
        WorkflowRuntime runtime = mock(WorkflowRuntime.class);
        when(runtime.run(eq("run-1"), any(), eq(Map.of("message", "hello"))))
                .thenReturn(new WorkflowRuntime.WorkflowExecutionResult(Map.of("answer", "ok"), List.of()));
        TraceService traceService = mock(TraceService.class);
        when(traceService.createRun(eq(RunType.WORKFLOW), any()))
                .thenReturn(new RunEntity("run-1", RunType.WORKFLOW, RunStatus.RUNNING, "{}", Instant.now()));
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()), runtime,
                traceService, definitionService);

        WorkflowRunResponse response = service.run(new WorkflowRunRequest(null, "wf-1", Map.of("message", "hello")));

        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.output()).isEqualTo(Map.of("answer", "ok"));
        verify(definitionService).resolveDefinition("wf-1");
        verify(traceService).markRunSucceeded(eq("run-1"), any());
    }

    @Test
    void rejectsRunRequestWithoutInlineDefinitionOrDefinitionId() {
        WorkflowService service = new WorkflowService(new WorkflowCompiler(new WorkflowNodeSchemaRegistry()),
                mock(WorkflowRuntime.class), mock(TraceService.class), mock(WorkflowDefinitionService.class));

        assertThatThrownBy(() -> service.run(new WorkflowRunRequest(null, null, Map.of())))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("WORKFLOW_DEFINITION_REQUIRED"));
    }

    private WorkflowDefinition validDefinition() {
        return new WorkflowDefinition(
                List.of(
                        new WorkflowNode("start", "start", Map.of()),
                        new WorkflowNode("end", "end", Map.of())),
                List.of(new WorkflowEdge("start", "end")));
    }

}
