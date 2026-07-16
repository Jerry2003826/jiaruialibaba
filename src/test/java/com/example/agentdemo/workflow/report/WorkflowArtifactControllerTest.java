package com.example.agentdemo.workflow.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowArtifactControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void downloadResponseUsesPrivateSafeHeadersAndUtf8FileName() throws Exception {
        Path file = tempDir.resolve("report.pdf");
        Files.write(file, "%PDF-".getBytes());
        WorkflowArtifactAccessService accessService = mock(WorkflowArtifactAccessService.class);
        Authentication authentication = mock(Authentication.class);
        when(accessService.open("art-1", authentication)).thenReturn(new WorkflowArtifactDownload(
                file, "研究报告.pdf", "application/pdf", Files.size(file), false));

        var response = new WorkflowArtifactController(accessService).content("art-1", authentication);

        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment;")
                .contains("filename*=");
        assertThat(response.getBody()).isNotNull();
    }
}
