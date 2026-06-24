package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WorkflowRuntimeConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(WorkflowNodeExecutor.class, () -> mock(WorkflowNodeExecutor.class))
            .withBean(TraceService.class, () -> mock(TraceService.class))
            .withUserConfiguration(WorkflowRuntimeConfig.class);

    @Test
    void selectsGraphRuntimeWhenConfigured() {
        contextRunner
                .withPropertyValues("demo.workflow.runtime=graph")
                .run(context -> assertThat(context.getBean(WorkflowRuntime.class))
                        .isInstanceOf(GraphWorkflowRuntime.class));
    }

    @Test
    void selectsSimpleRuntimeByDefault() {
        contextRunner
                .run(context -> assertThat(context.getBean(WorkflowRuntime.class))
                        .isInstanceOf(SimpleWorkflowRuntime.class));
    }

}
