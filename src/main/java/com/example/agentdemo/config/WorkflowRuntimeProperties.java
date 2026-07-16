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

    private Evaluation evaluation = new Evaluation();

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

    public Evaluation getEvaluation() {
        return evaluation;
    }

    public void setEvaluation(Evaluation evaluation) {
        this.evaluation = evaluation == null ? new Evaluation() : evaluation;
    }

    public static class Evaluation {

        private int maxCases = 12;

        private int concurrency = 2;

        private int queueCapacity = 24;

        private long caseDeadlineMs = 90_000L;

        private long overallDeadlineMs = 900_000L;

        public int getMaxCases() {
            return maxCases;
        }

        public void setMaxCases(int maxCases) {
            this.maxCases = maxCases;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getCaseDeadlineMs() {
            return caseDeadlineMs;
        }

        public void setCaseDeadlineMs(long caseDeadlineMs) {
            this.caseDeadlineMs = caseDeadlineMs;
        }

        public long getOverallDeadlineMs() {
            return overallDeadlineMs;
        }

        public void setOverallDeadlineMs(long overallDeadlineMs) {
            this.overallDeadlineMs = overallDeadlineMs;
        }
    }

}
