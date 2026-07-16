package com.example.agentdemo.workflow.report;

import java.nio.file.Path;

public record WorkflowArtifactDownload(
        Path path,
        String fileName,
        String mimeType,
        long sizeBytes,
        boolean inline) {
}
