package com.example.agentdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.workflow")
public class WorkflowRuntimeProperties {

    private String runtime = "simple";

    private boolean requirePublishedForRun = true;

    private boolean allowInlineRun = false;

    private int maxNodes = 100;

    private int maxEdges = 200;

    private int maxParallelBranches = 16;

    private int maxDynamicItems = 50;

    /**
     * Maximum number of node executions allowed for a single run (across the main path, parallel
     * branches, loop iterations, dynamic fan-out and nested subgraphs). Guards against runaway runs
     * that per-loop {@code maxIterations} cannot bound globally.
     */
    private int maxStepExecutions = 1000;

    /**
     * Wall-clock budget for a single run, in milliseconds.
     */
    private long runDeadlineMs = 120_000L;

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

    public boolean isAllowInlineRun() {
        return allowInlineRun;
    }

    public void setAllowInlineRun(boolean allowInlineRun) {
        this.allowInlineRun = allowInlineRun;
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

    public int getMaxStepExecutions() {
        return maxStepExecutions;
    }

    public void setMaxStepExecutions(int maxStepExecutions) {
        this.maxStepExecutions = maxStepExecutions;
    }

    public long getRunDeadlineMs() {
        return runDeadlineMs;
    }

    public void setRunDeadlineMs(long runDeadlineMs) {
        this.runDeadlineMs = runDeadlineMs;
    }

}
