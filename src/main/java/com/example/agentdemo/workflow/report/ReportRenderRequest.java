package com.example.agentdemo.workflow.report;

public record ReportRenderRequest(
        String title,
        String author,
        String organization,
        String markdown,
        String theme,
        String paperSize,
        String orientation,
        boolean includeToc,
        boolean includePageNumbers) {
}
