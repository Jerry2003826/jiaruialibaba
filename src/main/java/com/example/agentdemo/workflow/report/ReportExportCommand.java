package com.example.agentdemo.workflow.report;

import java.util.List;

public record ReportExportCommand(
        String runId,
        String nodeId,
        String ownerId,
        String originAppId,
        String fileName,
        int retentionDays,
        ReportRenderRequest renderRequest,
        List<ReportFormat> formats) {
}
