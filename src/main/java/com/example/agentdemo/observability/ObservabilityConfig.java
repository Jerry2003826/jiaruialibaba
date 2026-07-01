package com.example.agentdemo.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the {@link CorrelationIdFilter} ahead of Spring Security so the correlation id is present
 * for authentication/authorization logs as well as application logs.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(
                new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        // Run before the Spring Security filter chain (DEFAULT_FILTER_ORDER = -100).
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

}
