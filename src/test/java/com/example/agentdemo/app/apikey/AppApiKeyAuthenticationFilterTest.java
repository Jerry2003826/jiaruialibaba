package com.example.agentdemo.app.apikey;

import com.example.agentdemo.app.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppApiKeyAuthenticationFilterTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void firstUseUpdatesLastUsedAt() throws Exception {
        AppApiKeyRepository repository = Mockito.mock(AppApiKeyRepository.class);
        Instant now = Instant.parse("2026-07-01T10:00:00Z");
        AppApiKeyAuthenticationFilter filter = new AppApiKeyAuthenticationFilter(repository, new ObjectMapper(),
                appProperties(), fixedClock(now));
        String plaintext = "app_test_first";
        AppApiKeyEntity key = activeKey("app-1", plaintext);
        when(repository.findByKeyHashAndStatus(ApiKeySecrets.hash(plaintext), AppApiKeyStatus.ACTIVE))
                .thenReturn(Optional.of(key));

        filter.doFilter(request("app-1", plaintext), new MockHttpServletResponse(), new MockFilterChain());

        verify(repository).save(key);
        assertThat(key.getLastUsedAt()).isEqualTo(now);
    }

    @Test
    void repeatedUseWithinSixtySecondsDoesNotSaveAgain() throws Exception {
        AppApiKeyRepository repository = Mockito.mock(AppApiKeyRepository.class);
        Instant now = Instant.parse("2026-07-01T10:00:00Z");
        AppApiKeyAuthenticationFilter filter = new AppApiKeyAuthenticationFilter(repository, new ObjectMapper(),
                appProperties(), fixedClock(now));
        String plaintext = "app_test_recent";
        AppApiKeyEntity key = activeKey("app-1", plaintext);
        key.markUsed(now.minusSeconds(59));
        when(repository.findByKeyHashAndStatus(ApiKeySecrets.hash(plaintext), AppApiKeyStatus.ACTIVE))
                .thenReturn(Optional.of(key));

        filter.doFilter(request("app-1", plaintext), new MockHttpServletResponse(), new MockFilterChain());

        verify(repository, never()).save(any(AppApiKeyEntity.class));
        assertThat(key.getLastUsedAt()).isEqualTo(now.minusSeconds(59));
    }

    @Test
    void useAfterThrottleWindowSavesAgain() throws Exception {
        AppApiKeyRepository repository = Mockito.mock(AppApiKeyRepository.class);
        Instant now = Instant.parse("2026-07-01T10:00:00Z");
        AppApiKeyAuthenticationFilter filter = new AppApiKeyAuthenticationFilter(repository, new ObjectMapper(),
                appProperties(), fixedClock(now));
        String plaintext = "app_test_stale";
        AppApiKeyEntity key = activeKey("app-1", plaintext);
        key.markUsed(now.minusSeconds(60));
        when(repository.findByKeyHashAndStatus(ApiKeySecrets.hash(plaintext), AppApiKeyStatus.ACTIVE))
                .thenReturn(Optional.of(key));

        filter.doFilter(request("app-1", plaintext), new MockHttpServletResponse(), new MockFilterChain());

        verify(repository).save(key);
        assertThat(key.getLastUsedAt()).isEqualTo(now);
    }

    @Test
    void validKeyMayAuthenticateAnArtifactDownloadForDownstreamOwnershipChecks() throws Exception {
        AppApiKeyRepository repository = Mockito.mock(AppApiKeyRepository.class);
        Instant now = Instant.parse("2026-07-01T10:00:00Z");
        AppApiKeyAuthenticationFilter filter = new AppApiKeyAuthenticationFilter(repository, new ObjectMapper(),
                appProperties(), fixedClock(now));
        String plaintext = "app_test_artifact";
        AppApiKeyEntity key = activeKey("app-1", plaintext);
        when(repository.findByKeyHashAndStatus(ApiKeySecrets.hash(plaintext), AppApiKeyStatus.ACTIVE))
                .thenReturn(Optional.of(key));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/workflow-artifacts/art-1/content");
        request.addHeader(AppApiKeyAuthenticationFilter.API_KEY_HEADER, plaintext);
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .isInstanceOfSatisfying(AppApiKeyAuthenticationToken.class,
                        authentication -> assertThat(authentication.getAppId()).isEqualTo("app-1"));
    }

    private MockHttpServletRequest request(String appId, String plaintextKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/apps/" + appId + "/chat");
        request.addHeader(AppApiKeyAuthenticationFilter.API_KEY_HEADER, plaintextKey);
        return request;
    }

    private AppApiKeyEntity activeKey(String appId, String plaintextKey) {
        return new AppApiKeyEntity("ak_test", appId, ApiKeySecrets.hash(plaintextKey), "runtime", "app.run");
    }

    private AppProperties appProperties() {
        AppProperties properties = new AppProperties();
        properties.getApiKey().setLastUsedUpdateIntervalSeconds(60);
        return properties;
    }

    private Clock fixedClock(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
