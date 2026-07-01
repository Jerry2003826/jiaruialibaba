package com.example.agentdemo.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Knowledge-base ingestion limits.
 */
@Component
@ConfigurationProperties(prefix = "demo.knowledge")
public class KnowledgeProperties {

    /** Maximum uploaded file size in bytes (default 10 MB). */
    private long maxFileBytes = 10L * 1024 * 1024;

    /** Maximum extracted text length in characters (default 500k). */
    private int maxContentChars = 500_000;

    public long getMaxFileBytes() {
        return maxFileBytes;
    }

    public void setMaxFileBytes(long maxFileBytes) {
        this.maxFileBytes = maxFileBytes;
    }

    public int getMaxContentChars() {
        return maxContentChars;
    }

    public void setMaxContentChars(int maxContentChars) {
        this.maxContentChars = maxContentChars;
    }

}
