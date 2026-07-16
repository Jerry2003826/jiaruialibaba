package com.example.agentdemo.workflow.report;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowArtifactCleanupJobTest {

    @Test
    void deletesExpiredFileBeforeItsMetadata() {
        WorkflowArtifactRepository repository = mock(WorkflowArtifactRepository.class);
        WorkflowArtifactStorage storage = mock(WorkflowArtifactStorage.class);
        WorkflowArtifactEntity expired = new WorkflowArtifactEntity(
                "art-1", "exp-1", "owner-a", "run-1", null, "report-1",
                WorkflowArtifactRole.DOWNLOAD, "pdf", "report.pdf", "application/pdf", 4,
                "a".repeat(64), "exp-1/art-1.pdf", Instant.parse("2026-07-01T00:00:00Z"),
                Instant.parse("2026-07-02T00:00:00Z"));
        when(repository.findAllByExpiresAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(expired));

        new WorkflowArtifactCleanupJob(repository, storage).deleteExpiredArtifacts();

        verify(storage).delete("exp-1/art-1.pdf");
        verify(repository).delete(expired);
    }
}
