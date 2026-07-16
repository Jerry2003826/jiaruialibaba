package com.example.agentdemo.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRuntimePropertiesTest {

    @Test
    void bindsBoundedEvaluationProperties() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "demo.workflow.evaluation.max-cases", "12",
                "demo.workflow.evaluation.concurrency", "2",
                "demo.workflow.evaluation.queue-capacity", "24",
                "demo.workflow.evaluation.case-deadline-ms", "90000",
                "demo.workflow.evaluation.overall-deadline-ms", "900000"));

        WorkflowRuntimeProperties properties = new Binder(source)
                .bind("demo.workflow", Bindable.of(WorkflowRuntimeProperties.class))
                .get();

        assertThat(properties.getEvaluation().getMaxCases()).isEqualTo(12);
        assertThat(properties.getEvaluation().getConcurrency()).isEqualTo(2);
        assertThat(properties.getEvaluation().getQueueCapacity()).isEqualTo(24);
        assertThat(properties.getEvaluation().getCaseDeadlineMs()).isEqualTo(90_000L);
        assertThat(properties.getEvaluation().getOverallDeadlineMs()).isEqualTo(900_000L);
    }
}
