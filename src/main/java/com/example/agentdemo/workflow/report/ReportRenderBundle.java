package com.example.agentdemo.workflow.report;

import java.util.Map;

public record ReportRenderBundle(Map<ReportFormat, byte[]> files, byte[] printPreview) {

    public ReportRenderBundle {
        files = Map.copyOf(files);
        printPreview = printPreview.clone();
    }
}
