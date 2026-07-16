package com.example.agentdemo.workflow.report;

import java.util.Locale;

public enum ReportFormat {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    HTML("html", "text/html; charset=UTF-8"),
    MARKDOWN("md", "text/markdown; charset=UTF-8"),
    TXT("txt", "text/plain; charset=UTF-8");

    private final String extension;
    private final String mimeType;

    ReportFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String extension() {
        return extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public static ReportFormat fromConfig(Object value) {
        String normalized = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        return "MARKDOWN".equals(normalized) ? MARKDOWN : valueOf(normalized);
    }
}
