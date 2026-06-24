package com.example.agentdemo.workflow;

import com.example.agentdemo.common.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowControllerTest {

    @Test
    void listsWorkflowNodeSchemas() {
        WorkflowController controller = new WorkflowController(mock(WorkflowService.class),
                new WorkflowNodeSchemaRegistry());

        ApiResponse<List<WorkflowNodeSchema>> response = controller.listNodeSchemas();

        assertThat(response.success()).isTrue();
        assertThat(response.data())
                .extracting(WorkflowNodeSchema::type)
                .containsExactly("start", "retriever", "llm", "tool", "end");
    }

}
