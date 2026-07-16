package com.example.agentdemo.workflow.report;

import java.time.Instant;

public record ReportArtifactMetadata(
        String artifactId,
        String format,
        String fileName,
        String mimeType,
        long sizeBytes,
        String sha256,
        Instant expiresAt,
        String downloadUrl) {
}
