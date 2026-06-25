package com.example.agentdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.workflow")
public class WorkflowRuntimeProperties {

    private String runtime = "simple";

    private boolean requirePublishedForRun = true;

    public String getRuntime() {
        return runtime;
    }

    public void setRuntime(String runtime) {
        this.runtime = runtime;
    }

    public boolean isRequirePublishedForRun() {
        return requirePublishedForRun;
    }

    public void setRequirePublishedForRun(boolean requirePublishedForRun) {
        this.requirePublishedForRun = requirePublishedForRun;
    }

}
