package com.example.agentdemo.tool.tavily;

import com.example.agentdemo.common.ApiResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TavilySettingsControllerTest {

    @Test
    void configuresAndClearsOnlyRuntimeCredentialWithoutReturningTheSecret() {
        TavilyCredentialService credentials = new TavilyCredentialService("");
        TavilySettingsController controller = new TavilySettingsController(credentials);

        ApiResponse<TavilyCredentialStatus> configured = controller.configure(
                new TavilyCredentialRequest("tvly-controller-secret"));

        assertThat(configured.data()).isEqualTo(new TavilyCredentialStatus(true, "runtime"));
        assertThat(configured.toString()).doesNotContain("tvly-controller-secret");

        ApiResponse<TavilyCredentialStatus> cleared = controller.clearRuntimeCredential();
        assertThat(cleared.data()).isEqualTo(new TavilyCredentialStatus(false, "none"));
    }
}
