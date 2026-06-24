package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.TraceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WorkflowRuntimeConfig {

    @Bean
    @ConditionalOnProperty(prefix = "demo.workflow", name = "runtime", havingValue = "graph")
    public WorkflowRuntime graphWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService) {
        return new GraphWorkflowRuntime(nodeExecutor, traceService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "demo.workflow", name = "runtime", havingValue = "simple", matchIfMissing = true)
    public WorkflowRuntime simpleWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService) {
        return new SimpleWorkflowRuntime(nodeExecutor, traceService);
    }

}
