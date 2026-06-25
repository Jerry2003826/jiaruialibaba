package com.example.agentdemo.workflow;

import com.example.agentdemo.AgentBackendDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.active=",
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "demo.workflow.runtime=graph",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:graph_workflow_app_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
class GraphWorkflowApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void loadsGraphWorkflowRuntimeBean() {
        WorkflowRuntime runtime = applicationContext.getBean(WorkflowRuntime.class);

        assertThat(runtime).isInstanceOf(GraphWorkflowRuntime.class);
    }

}
