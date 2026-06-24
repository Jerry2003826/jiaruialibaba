package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.TraceService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Configuration
public class WorkflowRuntimeConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService workflowNodeExecutorService() {
        ThreadFactory threadFactory = Thread.ofVirtual()
                .name("workflow-node-", 0)
                .factory();
        return Executors.newThreadPerTaskExecutor(threadFactory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "demo.workflow", name = "runtime", havingValue = "graph")
    public WorkflowRuntime graphWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService,
            ExecutorService workflowNodeExecutorService) {
        return new GraphWorkflowRuntime(nodeExecutor, traceService, workflowNodeExecutorService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "demo.workflow", name = "runtime", havingValue = "simple", matchIfMissing = true)
    public WorkflowRuntime simpleWorkflowRuntime(WorkflowNodeExecutor nodeExecutor, TraceService traceService,
            ExecutorService workflowNodeExecutorService) {
        return new SimpleWorkflowRuntime(nodeExecutor, traceService, workflowNodeExecutorService);
    }

}
