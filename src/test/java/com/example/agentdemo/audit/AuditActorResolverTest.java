package com.example.agentdemo.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActorResolverTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void defaultsToRemoteAddressWhenForwardedHeadersAreNotTrusted() {
        AuditActorResolver resolver = new AuditActorResolver(new AuditProperties());
        MockHttpServletRequest request = request("198.51.100.10",
                "203.0.113.9, 198.51.100.50", "agent/" + "x".repeat(400));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        AuditActor actor = resolver.resolve();

        assertThat(actor.ip()).isEqualTo("198.51.100.10");
        assertThat(actor.userAgent()).hasSize(256).startsWith("agent/");
    }

    @Test
    void readsFirstForwardedAddressWhenTrustIsEnabled() {
        AuditProperties properties = new AuditProperties();
        properties.setTrustForwardedHeaders(true);
        AuditActorResolver resolver = new AuditActorResolver(properties);
        MockHttpServletRequest request = request("198.51.100.10",
                "203.0.113.9, 198.51.100.50", "agent/ok");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        AuditActor actor = resolver.resolve();

        assertThat(actor.ip()).isEqualTo("203.0.113.9");
        assertThat(actor.userAgent()).isEqualTo("agent/ok");
    }

    private MockHttpServletRequest request(String remoteAddr, String forwardedFor, String userAgent) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("X-Forwarded-For", forwardedFor);
        request.addHeader("User-Agent", userAgent);
        return request;
    }
}
