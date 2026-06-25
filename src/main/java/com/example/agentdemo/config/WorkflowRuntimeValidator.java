package com.example.agentdemo.config;

import com.example.agentdemo.common.BusinessException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class WorkflowRuntimeValidator implements InitializingBean {

    private static final Set<String> SUPPORTED_RUNTIMES = Set.of("simple", "graph");

    private final WorkflowRuntimeProperties workflowRuntimeProperties;

    public WorkflowRuntimeValidator(WorkflowRuntimeProperties workflowRuntimeProperties) {
        this.workflowRuntimeProperties = workflowRuntimeProperties;
    }

    @Override
    public void afterPropertiesSet() {
        String runtime = workflowRuntimeProperties.getRuntime();
        if (runtime == null || runtime.isBlank()) {
            throw new BusinessException("WORKFLOW_RUNTIME_INVALID",
                    "demo.workflow.runtime must be configured as 'simple' or 'graph'");
        }
        if (!SUPPORTED_RUNTIMES.contains(runtime)) {
            throw new BusinessException("WORKFLOW_RUNTIME_INVALID",
                    "Unsupported demo.workflow.runtime: " + runtime + ". Allowed values: simple, graph");
        }
    }

}
