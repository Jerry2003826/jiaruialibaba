package com.example.agentdemo.workflow;

import com.example.agentdemo.chat.AiModelResult;
import com.example.agentdemo.chat.AiModelService;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.tool.ToolService;
import com.example.agentdemo.trace.RunStepEntity;
import com.example.agentdemo.trace.StepStatus;
import com.example.agentdemo.trace.TraceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphWorkflowRuntimeTest {

    @Test
    void runsLinearWorkflowThroughSpringAiAlibabaGraph() {
        RagService ragService = mock(RagService.class);
        AiModelService aiModelService = mock(AiModelService.class);
        ToolService toolService = new ToolService();
        TraceService traceService = mock(TraceService.class);
        when(traceService.startStep(eq("run-1"), any(), any()))
                .thenAnswer(invocation -> step(invocation.getArgument(1)));
        when(aiModelService.generate(any(), any()))
                .thenReturn(AiModelResult.ok("graph answer"));

        GraphWorkflowRuntime runtime = new GraphWorkflowRuntime(
                new WorkflowNodeExecutor(ragService, aiModelService, toolService),
                traceService);

        WorkflowRuntime.WorkflowExecutionResult result = runtime.run("run-1", List.of(
                new WorkflowNode("start", "start", Map.of()),
                new WorkflowNode("llm_1", "llm", Map.of("prompt", "Question: {{input}}")),
                new WorkflowNode("end", "end", Map.of())
        ), Map.of("message", "hello graph"));

        assertThat(result.steps())
                .extracting(WorkflowStepSummary::nodeId)
                .containsExactly("start", "llm_1", "end");
        assertThat(result.output()).asString().contains("graph answer");
    }

    private static RunStepEntity step(String nodeName) {
        return new RunStepEntity("step-" + nodeName, "run-1", nodeName, "{}", StepStatus.RUNNING, Instant.now());
    }

}
