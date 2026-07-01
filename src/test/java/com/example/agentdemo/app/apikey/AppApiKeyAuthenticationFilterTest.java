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

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
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
        AppApiKeyAuthenticationFilter filter = new AppApiKeyAuthenticationFilter(repository, new ObjectMapper(),
                new AppProperties());
        String plaintext = "app_test_first";
        AppApiKeyEntity key = activeKey("app-1", plaintext);
        when(repository.findByKeyHashAndStatus(ApiKeySecrets.hash(plaintext), AppApiKeyStatus.ACTIVE))
                .thenReturn(Optional.of(key));

        filter.doFilter(request("app-1", plaintext), new MockHttpServletResponse(), new MockFilterChain());

        verify(repository).save(key);
    }

    @Test
    void repeatedUseWithinSixtySecondsDoesNotSaveAgain() throws Exception {
        AppApiKeyRepository repository = Mockito.mock(AppApiKeyRepository.class);
        AppApiKeyAuthenticationFilter filter = new AppApiKeyAuthenticationFilter(repository, new ObjectMapper(),
                new AppProperties());
        String plaintext = "app_test_recent";
        AppApiKeyEntity key = activeKey("app-1", plaintext);
        key.markUsed(Instant.now());
        when(repository.findByKeyHashAndStatus(ApiKeySecrets.hash(plaintext), AppApiKeyStatus.ACTIVE))
                .thenReturn(Optional.of(key));

        filter.doFilter(request("app-1", plaintext), new MockHttpServletResponse(), new MockFilterChain());

        verify(repository, never()).save(any(AppApiKeyEntity.class));
    }

    @Test
    void useAfterThrottleWindowSavesAgain() throws Exception {
        AppApiKeyRepository repository = Mockito.mock(AppApiKeyRepository.class);
        AppApiKeyAuthenticationFilter filter = new AppApiKeyAuthenticationFilter(repository, new ObjectMapper(),
                new AppProperties());
        String plaintext = "app_test_stale";
        AppApiKeyEntity key = activeKey("app-1", plaintext);
        key.markUsed(Instant.now().minusSeconds(61));
        when(repository.findByKeyHashAndStatus(ApiKeySecrets.hash(plaintext), AppApiKeyStatus.ACTIVE))
                .thenReturn(Optional.of(key));

        filter.doFilter(request("app-1", plaintext), new MockHttpServletResponse(), new MockFilterChain());

        verify(repository).save(key);
    }

    private MockHttpServletRequest request(String appId, String plaintextKey) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/apps/" + appId + "/chat");
        request.addHeader(AppApiKeyAuthenticationFilter.API_KEY_HEADER, plaintextKey);
        return request;
    }

    private AppApiKeyEntity activeKey(String appId, String plaintextKey) {
        return new AppApiKeyEntity("ak_test", appId, ApiKeySecrets.hash(plaintextKey), "runtime", "app.run");
    }
}
