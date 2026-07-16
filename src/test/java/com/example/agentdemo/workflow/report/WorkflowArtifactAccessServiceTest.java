package com.example.agentdemo.workflow.report;

import com.example.agentdemo.app.apikey.AppApiKeyAuthenticationToken;
import com.example.agentdemo.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowArtifactAccessServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void appApiKeyCanOnlyDownloadArtifactsCreatedByItsOwnApp() {
        WorkflowArtifactRepository repository = mock(WorkflowArtifactRepository.class);
        WorkflowArtifactProperties properties = new WorkflowArtifactProperties();
        properties.setStorageRoot(tempDir.toString());
        LocalWorkflowArtifactStorage storage = new LocalWorkflowArtifactStorage(properties);
        WorkflowArtifactEntity entity = entity("app-a", Instant.parse("2026-08-01T00:00:00Z"));
        storage.store(entity.getStorageKey(), "report".getBytes());
        when(repository.findByArtifactIdAndOwnerId("art-1", "owner-a")).thenReturn(Optional.of(entity));
        WorkflowArtifactAccessService service = new WorkflowArtifactAccessService(repository, storage,
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));

        AppApiKeyAuthenticationToken ownKey = new AppApiKeyAuthenticationToken(
                "owner-a", "app-a", "key-a", List.of(new SimpleGrantedAuthority("SCOPE_app.run")));
        assertThat(service.open("art-1", ownKey).path()).isRegularFile();

        AppApiKeyAuthenticationToken otherKey = new AppApiKeyAuthenticationToken(
                "owner-a", "app-b", "key-b", List.of(new SimpleGrantedAuthority("SCOPE_app.run")));
        assertThatThrownBy(() -> service.open("art-1", otherKey))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void expiredArtifactCannotBeDownloaded() {
        WorkflowArtifactRepository repository = mock(WorkflowArtifactRepository.class);
        WorkflowArtifactProperties properties = new WorkflowArtifactProperties();
        properties.setStorageRoot(tempDir.toString());
        LocalWorkflowArtifactStorage storage = new LocalWorkflowArtifactStorage(properties);
        WorkflowArtifactEntity entity = entity(null, Instant.parse("2026-07-14T00:00:00Z"));
        when(repository.findByArtifactIdAndOwnerId("art-1", "owner-a")).thenReturn(Optional.of(entity));
        WorkflowArtifactAccessService service = new WorkflowArtifactAccessService(repository, storage,
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
        var user = new UsernamePasswordAuthenticationToken("owner-a", "", List.of(
                new SimpleGrantedAuthority("SCOPE_workflow.read")));

        assertThatThrownBy(() -> service.open("art-1", user))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("ARTIFACT_EXPIRED");
    }

    @Test
    void jwtDownloadRequiresWorkflowReadScope() {
        WorkflowArtifactRepository repository = mock(WorkflowArtifactRepository.class);
        WorkflowArtifactProperties properties = new WorkflowArtifactProperties();
        properties.setStorageRoot(tempDir.toString());
        LocalWorkflowArtifactStorage storage = new LocalWorkflowArtifactStorage(properties);
        WorkflowArtifactAccessService service = new WorkflowArtifactAccessService(repository, storage,
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
        var user = new UsernamePasswordAuthenticationToken("owner-a", "", List.of(
                new SimpleGrantedAuthority("SCOPE_workflow.run")));

        assertThatThrownBy(() -> service.open("art-1", user))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("workflow.read");
    }

    @Test
    void runHistoryGroupsDownloadFilesWithItsPrintPreview() {
        WorkflowArtifactRepository repository = mock(WorkflowArtifactRepository.class);
        WorkflowArtifactProperties properties = new WorkflowArtifactProperties();
        properties.setStorageRoot(tempDir.toString());
        LocalWorkflowArtifactStorage storage = new LocalWorkflowArtifactStorage(properties);
        WorkflowArtifactEntity pdf = entity("art-pdf", "exp-1", WorkflowArtifactRole.DOWNLOAD,
                "pdf", "报告.pdf", Instant.parse("2026-08-01T00:00:00Z"));
        WorkflowArtifactEntity preview = entity("art-preview", "exp-1", WorkflowArtifactRole.PRINT_PREVIEW,
                "html", "报告-打印预览.html", Instant.parse("2026-08-01T00:00:00Z"));
        when(repository.findAllByRunIdAndOwnerIdOrderByCreatedAtAsc("run-1", "owner-a"))
                .thenReturn(List.of(pdf, preview));
        WorkflowArtifactAccessService service = new WorkflowArtifactAccessService(repository, storage,
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
        var user = new UsernamePasswordAuthenticationToken("owner-a", "", List.of(
                new SimpleGrantedAuthority("SCOPE_workflow.read")));

        List<WorkflowArtifactGroupResponse> groups = service.listRunArtifacts("run-1", user);

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.exportId()).isEqualTo("exp-1");
            assertThat(group.artifacts()).extracting(ReportArtifactMetadata::artifactId)
                    .containsExactly("art-pdf");
            assertThat(group.primary().artifactId()).isEqualTo("art-pdf");
            assertThat(group.printPreview().artifactId()).isEqualTo("art-preview");
            assertThat(group.printPreview().contentUrl())
                    .isEqualTo("/api/workflow-artifacts/art-preview/content");
        });
    }

    private WorkflowArtifactEntity entity(String appId, Instant expiresAt) {
        return entity("art-1", "exp-1", WorkflowArtifactRole.DOWNLOAD, "pdf", "报告.pdf", expiresAt, appId);
    }

    private WorkflowArtifactEntity entity(String artifactId, String exportId, WorkflowArtifactRole role,
            String format, String fileName, Instant expiresAt) {
        return entity(artifactId, exportId, role, format, fileName, expiresAt, null);
    }

    private WorkflowArtifactEntity entity(String artifactId, String exportId, WorkflowArtifactRole role,
            String format, String fileName, Instant expiresAt, String appId) {
        return new WorkflowArtifactEntity(artifactId, exportId, "owner-a", "run-1", appId, "report-1",
                role, format, fileName, "html".equals(format) ? "text/html" : "application/pdf", 6,
                "a".repeat(64), exportId + "/" + artifactId + "." + format,
                Instant.parse("2026-07-15T00:00:00Z"), expiresAt);
    }
}
