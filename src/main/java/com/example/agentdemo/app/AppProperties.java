package com.example.agentdemo.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * App runtime policy. {@code requirePublishedForRun} (default true) forces runtime invocations to
 * use the published, immutable revision snapshot; when false (local/dev only) the current draft
 * may be run for quick iteration.
 */
@Component
@ConfigurationProperties(prefix = "demo.app")
public class AppProperties {

    private boolean requirePublishedForRun = true;
    private int maxRunInputBytes = 64 * 1024;
    private ApiKey apiKey = new ApiKey();

    public boolean isRequirePublishedForRun() {
        return requirePublishedForRun;
    }

    public void setRequirePublishedForRun(boolean requirePublishedForRun) {
        this.requirePublishedForRun = requirePublishedForRun;
    }

    public int getMaxRunInputBytes() {
        return maxRunInputBytes;
    }

    public void setMaxRunInputBytes(int maxRunInputBytes) {
        this.maxRunInputBytes = maxRunInputBytes;
    }

    public ApiKey getApiKey() {
        return apiKey;
    }

    public void setApiKey(ApiKey apiKey) {
        this.apiKey = apiKey;
    }

    public static class ApiKey {

        private long lastUsedUpdateIntervalSeconds = 60;

        public long getLastUsedUpdateIntervalSeconds() {
            return lastUsedUpdateIntervalSeconds;
        }

        public void setLastUsedUpdateIntervalSeconds(long lastUsedUpdateIntervalSeconds) {
            this.lastUsedUpdateIntervalSeconds = lastUsedUpdateIntervalSeconds;
        }
    }

}
