package com.example.agentdemo.tool.tavily;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TavilyCredentialServiceTest {

    @Test
    void runtimeConfigurationIsReportedWithoutExposingTheSecret() {
        TavilyCredentialService credentials = new TavilyCredentialService("");

        credentials.configure("tvly-user-secret");

        TavilyCredentialStatus status = credentials.status();
        assertThat(status.configured()).isTrue();
        assertThat(status.source()).isEqualTo("runtime");
        assertThat(status.toString()).doesNotContain("tvly-user-secret");
        assertThat(credentials.requireApiKey()).isEqualTo("tvly-user-secret");
    }

    @Test
    void missingCredentialFailsExplicitlyInsteadOfFallingBackToSimulatedSearch() {
        TavilyCredentialService credentials = new TavilyCredentialService("");

        assertThatThrownBy(credentials::requireApiKey)
                .hasMessageContaining("Tavily API key")
                .hasMessageContaining("Settings");
    }

    @Test
    void clearingRuntimeCredentialFallsBackToEnvironmentConfiguration() {
        TavilyCredentialService credentials = new TavilyCredentialService("tvly-env-secret");
        credentials.configure("tvly-runtime-secret");

        credentials.clearRuntimeCredential();

        assertThat(credentials.status()).isEqualTo(new TavilyCredentialStatus(true, "environment"));
        assertThat(credentials.requireApiKey()).isEqualTo("tvly-env-secret");
    }
}
