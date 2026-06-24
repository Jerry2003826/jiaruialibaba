package com.example.agentdemo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.ai.dashscope.api-key=",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
class AgentBackendDemoApplicationTest {

    @Test
    void contextLoads() {
    }

}
