package com.example.agentdemo.tool.tavily;

import com.example.agentdemo.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class TavilyCredentialService {

    private final String environmentApiKey;
    private final AtomicReference<String> runtimeApiKey = new AtomicReference<>();

    public TavilyCredentialService(@Value("${demo.tavily.api-key:}") String environmentApiKey) {
        this.environmentApiKey = normalize(environmentApiKey);
    }

    public TavilyCredentialStatus status() {
        if (StringUtils.hasText(runtimeApiKey.get())) {
            return new TavilyCredentialStatus(true, "runtime");
        }
        if (StringUtils.hasText(environmentApiKey)) {
            return new TavilyCredentialStatus(true, "environment");
        }
        return new TavilyCredentialStatus(false, "none");
    }

    public void configure(String apiKey) {
        String normalized = normalize(apiKey);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException("TAVILY_CREDENTIAL_INVALID", "Tavily API key is required");
        }
        runtimeApiKey.set(normalized);
    }

    public void clearRuntimeCredential() {
        runtimeApiKey.set(null);
    }

    public String requireApiKey() {
        String runtime = runtimeApiKey.get();
        if (StringUtils.hasText(runtime)) {
            return runtime;
        }
        if (StringUtils.hasText(environmentApiKey)) {
            return environmentApiKey;
        }
        throw new BusinessException("TAVILY_NOT_CONFIGURED",
                "Tavily API key is not configured. Configure it in Settings before running this node.");
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
