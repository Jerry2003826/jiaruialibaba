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

    public boolean isRequirePublishedForRun() {
        return requirePublishedForRun;
    }

    public void setRequirePublishedForRun(boolean requirePublishedForRun) {
        this.requirePublishedForRun = requirePublishedForRun;
    }

}
