package com.example.agentdemo.rag.vector;

import com.example.agentdemo.config.RagProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DashVectorGatewayFilterTest {

    @Test
    void rendersBuilderKnowledgeBaseExclusionAsDashVectorNotEqualsFilter() {
        DashVectorGateway gateway = new DashVectorGateway(new RagProperties());

        assertThat(gateway.metadataFilterForTest(Map.of(
                "ownerId", "owner'quoted",
                "excludeKbId", "kb-builder")))
                .isEqualTo("ownerId = 'owner\\'quoted' and kbId != 'kb-builder'");
    }
}
