package com.example.agentdemo.support;

import com.example.agentdemo.config.AlibabaProperties;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.config.RagProperties;

public final class TestAlibabaPolicies {

    private TestAlibabaPolicies() {
    }

    public static AlibabaRuntimePolicy legacyFallbackAllowed() {
        return legacyFallbackAllowed(true);
    }

    public static AlibabaRuntimePolicy legacyFallbackAllowed(boolean keywordFallbackEnabled) {
        AlibabaProperties properties = new AlibabaProperties();
        properties.setStrictMode(false);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setKeywordFallbackEnabled(keywordFallbackEnabled);
        return new AlibabaRuntimePolicy(properties, ragProperties, true);
    }

    public static AlibabaRuntimePolicy strictMode() {
        AlibabaProperties properties = new AlibabaProperties();
        properties.setStrictMode(true);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setKeywordFallbackEnabled(false);
        return new AlibabaRuntimePolicy(properties, ragProperties, false);
    }

    public static AlibabaRuntimePolicy fallbackDisabled() {
        AlibabaProperties properties = new AlibabaProperties();
        properties.setStrictMode(false);
        RagProperties ragProperties = new RagProperties();
        ragProperties.getRag().setKeywordFallbackEnabled(false);
        return new AlibabaRuntimePolicy(properties, ragProperties, false);
    }

}
