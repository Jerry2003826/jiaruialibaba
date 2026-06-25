package com.example.agentdemo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Central policy for Alibaba strict mode and legacy demo fallback behavior.
 */
@Component
public class AlibabaRuntimePolicy {

    private final AlibabaProperties alibabaProperties;
    private final RagProperties ragProperties;
    private final boolean fallbackEnabled;

    public AlibabaRuntimePolicy(AlibabaProperties alibabaProperties, RagProperties ragProperties,
            @Value("${demo.ai.fallback-enabled:true}") boolean fallbackEnabled) {
        this.alibabaProperties = alibabaProperties;
        this.ragProperties = ragProperties;
        this.fallbackEnabled = fallbackEnabled;
    }

    public boolean isStrictMode() {
        return alibabaProperties.isStrictMode();
    }

    public boolean isLegacyFallbackAllowed() {
        return !isStrictMode() && fallbackEnabled;
    }

    public boolean isKeywordFallbackAllowed() {
        return !isStrictMode() && ragProperties.getRag().isKeywordFallbackEnabled();
    }

    /**
     * When true, the full Alibaba stack must be configured and startup validation runs.
     */
    public boolean isAlibabaStackRequired() {
        return isStrictMode() || !fallbackEnabled;
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

}
