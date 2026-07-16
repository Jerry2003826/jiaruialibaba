package com.example.agentdemo.workflow.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "demo.workflow.http")
public class WorkflowHttpProperties {

    private String credentialsMasterKey;
    private List<String> allowedHosts = new ArrayList<>();
    private int maxResponseBytes = 2 * 1024 * 1024;
    private int hardMaxResponseBytes = 10 * 1024 * 1024;
    private int maxRedirects = 3;

    public String getCredentialsMasterKey() {
        return credentialsMasterKey;
    }

    public void setCredentialsMasterKey(String credentialsMasterKey) {
        this.credentialsMasterKey = credentialsMasterKey;
    }

    public List<String> getAllowedHosts() {
        return List.copyOf(allowedHosts);
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new ArrayList<>() : new ArrayList<>(allowedHosts);
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public int getHardMaxResponseBytes() {
        return hardMaxResponseBytes;
    }

    public void setHardMaxResponseBytes(int hardMaxResponseBytes) {
        this.hardMaxResponseBytes = hardMaxResponseBytes;
    }

    public int getMaxRedirects() {
        return maxRedirects;
    }

    public void setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
    }

    public int effectiveMaxResponseBytes() {
        int hardLimit = Math.max(1, Math.min(hardMaxResponseBytes, 10 * 1024 * 1024));
        return Math.max(1, Math.min(maxResponseBytes, hardLimit));
    }

    public int effectiveMaxRedirects() {
        return Math.max(0, Math.min(maxRedirects, 3));
    }
}
