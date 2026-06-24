package com.example.agentdemo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:mcp_enabled_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword",
        "demo.mcp.enabled=true",
        "spring.ai.mcp.client.enabled=true",
        "spring.ai.mcp.client.toolcallback.enabled=true",
        "spring.ai.mcp.client.annotation-scanner.enabled=false"
})
class McpEnabledApplicationTest {

    @Test
    void contextLoadsWithMcpEnabledAndNoRemoteServers() {
    }

}
