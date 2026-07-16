package com.example.agentdemo.workflow.report;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "demo.workflow.artifacts")
public class WorkflowArtifactProperties {

    private String storageRoot = "var/workflow-artifacts";
    private int defaultRetentionDays = 30;
    private int maxRetentionDays = 365;
    private int maxSourceChars = 500_000;
    private long maxFileBytes = 20L * 1024L * 1024L;
    private long maxBatchBytes = 50L * 1024L * 1024L;

    public String getStorageRoot() { return storageRoot; }
    public void setStorageRoot(String storageRoot) { this.storageRoot = storageRoot; }
    public int getDefaultRetentionDays() { return defaultRetentionDays; }
    public void setDefaultRetentionDays(int defaultRetentionDays) { this.defaultRetentionDays = defaultRetentionDays; }
    public int getMaxRetentionDays() { return maxRetentionDays; }
    public void setMaxRetentionDays(int maxRetentionDays) { this.maxRetentionDays = maxRetentionDays; }
    public int getMaxSourceChars() { return maxSourceChars; }
    public void setMaxSourceChars(int maxSourceChars) { this.maxSourceChars = maxSourceChars; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public long getMaxBatchBytes() { return maxBatchBytes; }
    public void setMaxBatchBytes(long maxBatchBytes) { this.maxBatchBytes = maxBatchBytes; }
}
