package com.example.agentdemo.config;

import com.example.agentdemo.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowRuntimeValidatorTest {

    @Test
    void acceptsSimpleRuntime() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setRuntime("simple");
        WorkflowRuntimeValidator validator = new WorkflowRuntimeValidator(properties);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void acceptsGraphRuntime() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setRuntime("graph");
        WorkflowRuntimeValidator validator = new WorkflowRuntimeValidator(properties);

        assertThatCode(validator::afterPropertiesSet).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnsupportedRuntime() {
        WorkflowRuntimeProperties properties = new WorkflowRuntimeProperties();
        properties.setRuntime("banana");
        WorkflowRuntimeValidator validator = new WorkflowRuntimeValidator(properties);

        assertThatThrownBy(validator::afterPropertiesSet)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported demo.workflow.runtime: banana");
    }

}
