package com.example.agentdemo.workflow.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowReportExportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void removesPathSegmentsAndDangerousExtensionsFromFileNames() {
        assertThat(WorkflowReportExportService.safeBaseName("../../quarterly-report.exe"))
                .isEqualTo("quarterly-report");
        assertThat(WorkflowReportExportService.safeBaseName("report.pdf"))
                .isEqualTo("report");
    }

    @Test
    void createsDownloadArtifactsAndAnInternalPrintPreview() throws Exception {
        WorkflowArtifactProperties properties = new WorkflowArtifactProperties();
        properties.setStorageRoot(tempDir.toString());
        WorkflowArtifactRepository repository = mock(WorkflowArtifactRepository.class);
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        WorkflowReportExportService service = new WorkflowReportExportService(
                new ReportDocumentRenderer(), repository, new LocalWorkflowArtifactStorage(properties),
                properties, Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));

        ReportExportResult result = service.export(new ReportExportCommand(
                "run-1", "report-1", "owner-a", "app-a", "../研究报告.pdf", 30,
                new ReportRenderRequest("研究报告", "", "", "# 结论\n\n内容", "business", "A4",
                        "portrait", true, true),
                List.of(ReportFormat.PDF, ReportFormat.DOCX)));

        assertThat(result.artifacts()).hasSize(2);
        assertThat(result.artifacts()).extracting(ReportArtifactMetadata::fileName)
                .containsExactly("研究报告.pdf", "研究报告.docx");
        assertThat(result.printPreview().contentUrl()).isEqualTo(
                "/api/workflow-artifacts/" + result.printPreview().artifactId() + "/content");
        assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-08-14T00:00:00Z"));
        assertThat(Files.walk(tempDir).filter(Files::isRegularFile)).hasSize(3);
        assertThat(result.artifacts()).allSatisfy(artifact -> {
            assertThat(artifact.downloadUrl()).isEqualTo(
                    "/api/workflow-artifacts/" + artifact.artifactId() + "/content");
            assertThat(artifact.sha256()).hasSize(64);
            assertThat(artifact.sizeBytes()).isPositive();
        });
    }

    @Test
    void removesAlreadyStoredFilesWhenAnyFormatCannotBePersisted() {
        WorkflowArtifactProperties properties = new WorkflowArtifactProperties();
        ReportDocumentRenderer renderer = mock(ReportDocumentRenderer.class);
        LinkedHashMap<ReportFormat, byte[]> files = new LinkedHashMap<>();
        files.put(ReportFormat.PDF, "%PDF".getBytes());
        files.put(ReportFormat.DOCX, new byte[] { 'P', 'K' });
        when(renderer.render(org.mockito.ArgumentMatchers.any(), anyList()))
                .thenReturn(new ReportRenderBundle(files, "<html></html>".getBytes()));
        WorkflowArtifactRepository repository = mock(WorkflowArtifactRepository.class);
        List<String> stored = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        WorkflowArtifactStorage storage = new WorkflowArtifactStorage() {
            @Override
            public void store(String storageKey, byte[] content) {
                if (!stored.isEmpty()) throw new IllegalStateException("disk full");
                stored.add(storageKey);
            }

            @Override
            public Path resolve(String storageKey) { return tempDir.resolve(storageKey); }

            @Override
            public void delete(String storageKey) { deleted.add(storageKey); }
        };
        WorkflowReportExportService service = new WorkflowReportExportService(renderer, repository, storage,
                properties, Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.export(new ReportExportCommand(
                        "run-1", "report-1", "owner-a", null, "report", 30,
                        new ReportRenderRequest("Report", "", "", "content", "business", "A4",
                                "portrait", true, true),
                        List.of(ReportFormat.PDF, ReportFormat.DOCX))))
                .isInstanceOf(com.example.agentdemo.common.BusinessException.class)
                .extracting(error -> ((com.example.agentdemo.common.BusinessException) error).getCode())
                .isEqualTo("REPORT_EXPORT_FAILED");
        assertThat(deleted).containsExactlyElementsOf(stored);
        verifyNoInteractions(repository);
    }
}
