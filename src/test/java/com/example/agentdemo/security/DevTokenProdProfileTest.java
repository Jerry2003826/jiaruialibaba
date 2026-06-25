package com.example.agentdemo.security;

import com.example.agentdemo.AgentBackendDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Guards the fix for the dev-token fail-open: under the {@code prod} profile the anonymous,
 * full-scope {@code GET /api/auth/dev-token} endpoint must not be registered at all (so it cannot
 * be used to mint a privileged token without credentials), while the rest of the API stays secured.
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_prod_devtoken_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class DevTokenProdProfileTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void devAuthControllerBeanIsNotRegisteredUnderProdProfile() {
        // @Profile("!prod") must exclude the controller entirely so the anonymous full-scope token
        // endpoint cannot be reached at all under prod (it never mints a token).
        assertThat(applicationContext.getBeanNamesForType(DevAuthController.class)).isEmpty();
    }

    @Test
    void securedEndpointsStillRequireAuthenticationUnderProdProfile() throws Exception {
        mockMvc.perform(get("/api/runs"))
                .andExpect(status().isUnauthorized());
    }
}
