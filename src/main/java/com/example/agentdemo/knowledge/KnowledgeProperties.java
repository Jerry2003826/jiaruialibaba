package com.example.agentdemo.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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

    /** Maximum number of documents scanned during keyword-first retrieval. */
    private int maxScannedDocuments = 5_000;

    /** Allowlist for file MIME types accepted by KB ingestion. */
    private List<String> allowedMimeTypes = new ArrayList<>(List.of(
            "text/plain",
            "text/markdown",
            "text/csv",
            "text/html",
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/msword",
            "application/vnd.ms-powerpoint"));

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

    public int getMaxScannedDocuments() {
        return maxScannedDocuments;
    }

    public void setMaxScannedDocuments(int maxScannedDocuments) {
        this.maxScannedDocuments = maxScannedDocuments;
    }

    public List<String> getAllowedMimeTypes() {
        return allowedMimeTypes;
    }

    public void setAllowedMimeTypes(List<String> allowedMimeTypes) {
        this.allowedMimeTypes = allowedMimeTypes == null ? new ArrayList<>() : new ArrayList<>(allowedMimeTypes);
    }

}
