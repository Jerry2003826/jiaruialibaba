package com.example.agentdemo.workflow.report;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record ReportExportResult(
        String exportId,
        List<ReportArtifactMetadata> artifacts,
        ReportArtifactMetadata primary,
        ReportPrintPreviewMetadata printPreview,
        Instant expiresAt) {

    public Map<String, Object> toOutput() {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("exportId", exportId);
        output.put("artifacts", artifacts);
        output.put("primary", primary);
        output.put("printPreview", printPreview);
        output.put("expiresAt", expiresAt);
        return output;
    }
}
