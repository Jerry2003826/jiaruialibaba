package com.example.agentdemo.workflow.report;

import java.time.Instant;
import java.util.List;

public record WorkflowArtifactGroupResponse(
        String exportId,
        List<ReportArtifactMetadata> artifacts,
        ReportArtifactMetadata primary,
        ReportPrintPreviewMetadata printPreview,
        Instant expiresAt) {
}
