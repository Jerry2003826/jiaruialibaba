package com.example.agentdemo.workflow.report;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;

public record ReportRenderBundle(Map<ReportFormat, byte[]> files, byte[] printPreview) {

    public ReportRenderBundle {
        files = Map.copyOf(files);
        printPreview = printPreview.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ReportRenderBundle bundle) || !Arrays.equals(printPreview, bundle.printPreview)) {
            return false;
        }
        return files.size() == bundle.files.size()
                && files.entrySet().stream()
                        .allMatch(entry -> Arrays.equals(entry.getValue(), bundle.files.get(entry.getKey())));
    }

    @Override
    public int hashCode() {
        int filesHash = files.entrySet().stream()
                .mapToInt(entry -> 31 * Objects.hashCode(entry.getKey()) + Arrays.hashCode(entry.getValue()))
                .sum();
        return 31 * filesHash + Arrays.hashCode(printPreview);
    }

    @Override
    public String toString() {
        return "ReportRenderBundle[formats=%s, printPreviewLength=%d]"
                .formatted(files.keySet(), printPreview.length);
    }
}
