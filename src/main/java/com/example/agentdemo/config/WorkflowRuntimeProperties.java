package com.example.agentdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.workflow")
public class WorkflowRuntimeProperties {

    private String runtime = "simple";

    private boolean requirePublishedForRun = true;

    private int maxNodes = 100;

    private int maxEdges = 200;

    private int maxParallelBranches = 16;

    private int maxDynamicItems = 50;

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

    public int getMaxNodes() {
        return maxNodes;
    }

    public void setMaxNodes(int maxNodes) {
        this.maxNodes = maxNodes;
    }

    public int getMaxEdges() {
        return maxEdges;
    }

    public void setMaxEdges(int maxEdges) {
        this.maxEdges = maxEdges;
    }

    public int getMaxParallelBranches() {
        return maxParallelBranches;
    }

    public void setMaxParallelBranches(int maxParallelBranches) {
        this.maxParallelBranches = maxParallelBranches;
    }

    public int getMaxDynamicItems() {
        return maxDynamicItems;
    }

    public void setMaxDynamicItems(int maxDynamicItems) {
        this.maxDynamicItems = maxDynamicItems;
    }

}
