package com.example.agentdemo.app;

import com.example.agentdemo.app.dto.AppRunRequest;
import com.example.agentdemo.app.dto.AppRunResultResponse;
import com.example.agentdemo.trace.RunContext;
import com.example.agentdemo.workflow.WorkflowRunRequest;
import com.example.agentdemo.workflow.WorkflowRunResponse;
import com.example.agentdemo.workflow.WorkflowService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowAppRunnerTest {

    @Test
    void setsAndClearsRunContextAroundWorkflowRun() {
        WorkflowService workflowService = mock(WorkflowService.class);
        WorkflowAppRunner runner = new WorkflowAppRunner(workflowService);
        AppSnapshot snapshot = new AppSnapshot("Flow", null, AppType.WORKFLOW, AppConfig.empty(), "wf-1", 3);
        when(workflowService.run(any(WorkflowRunRequest.class))).thenAnswer(invocation -> {
            WorkflowRunRequest request = invocation.getArgument(0);
            assertThat(RunContext.currentAppId()).isEqualTo("app-1");
            assertThat(request.definitionId()).isEqualTo("wf-1");
            assertThat(request.definitionVersion()).isEqualTo(3);
            assertThat(request.input()).containsEntry("customer", "Ada");
            return new WorkflowRunResponse(Map.of("ok", true), "run-1", List.of(), "wf-1", 3);
        });

        AppRunResultResponse response = runner.run("app-1", snapshot,
                new AppRunRequest(Map.of("customer", "Ada")));

        assertThat(response.runId()).isEqualTo("run-1");
        assertThat(response.workflowDefinitionId()).isEqualTo("wf-1");
        assertThat(RunContext.currentAppId()).isNull();
    }

}
