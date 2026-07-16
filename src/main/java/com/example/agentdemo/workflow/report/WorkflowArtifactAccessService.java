package com.example.agentdemo.workflow.report;

import com.example.agentdemo.app.apikey.AppApiKeyAuthenticationToken;
import com.example.agentdemo.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowArtifactAccessService {

    private final WorkflowArtifactRepository repository;
    private final WorkflowArtifactStorage storage;
    private final Clock clock;

    @Autowired
    public WorkflowArtifactAccessService(WorkflowArtifactRepository repository, WorkflowArtifactStorage storage) {
        this(repository, storage, Clock.systemUTC());
    }

    WorkflowArtifactAccessService(WorkflowArtifactRepository repository, WorkflowArtifactStorage storage, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    @Transactional
    public WorkflowArtifactDownload open(String artifactId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication is required");
        }
        if (!(authentication instanceof AppApiKeyAuthenticationToken)
                && authentication.getAuthorities().stream()
                        .noneMatch(authority -> "SCOPE_workflow.read".equals(authority.getAuthority()))) {
            throw new AccessDeniedException("workflow.read authority is required");
        }
        WorkflowArtifactEntity entity = repository.findByArtifactIdAndOwnerId(artifactId, authentication.getName())
                .orElseThrow(() -> new BusinessException("ARTIFACT_NOT_FOUND", "Report artifact was not found"));
        if (authentication instanceof AppApiKeyAuthenticationToken appKey
                && (entity.getAppId() == null || !entity.getAppId().equals(appKey.getAppId()))) {
            throw new AccessDeniedException("App API key cannot access this report artifact");
        }
        if (!entity.getExpiresAt().isAfter(clock.instant())) {
            storage.delete(entity.getStorageKey());
            repository.delete(entity);
            throw new BusinessException("ARTIFACT_EXPIRED", "Report artifact has expired");
        }
        return new WorkflowArtifactDownload(storage.resolve(entity.getStorageKey()), entity.getFileName(),
                entity.getMimeType(), entity.getSizeBytes(), entity.getRole() == WorkflowArtifactRole.PRINT_PREVIEW);
    }

    @Transactional(readOnly = true)
    public List<WorkflowArtifactGroupResponse> listRunArtifacts(String runId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AppApiKeyAuthenticationToken
                || authentication.getAuthorities().stream()
                        .noneMatch(authority -> "SCOPE_workflow.read".equals(authority.getAuthority()))) {
            throw new AccessDeniedException("Workflow read access is required");
        }
        Map<String, List<WorkflowArtifactEntity>> grouped = repository
                .findAllByRunIdAndOwnerIdOrderByCreatedAtAsc(runId, authentication.getName()).stream()
                .filter(entity -> entity.getExpiresAt().isAfter(clock.instant()))
                .collect(java.util.stream.Collectors.groupingBy(WorkflowArtifactEntity::getExportId,
                        LinkedHashMap::new, java.util.stream.Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> group(entry.getKey(), entry.getValue()))
                .toList();
    }

    private WorkflowArtifactGroupResponse group(String exportId, List<WorkflowArtifactEntity> entities) {
        List<ReportArtifactMetadata> downloads = entities.stream()
                .filter(entity -> entity.getRole() == WorkflowArtifactRole.DOWNLOAD)
                .map(this::metadata)
                .toList();
        ReportPrintPreviewMetadata preview = entities.stream()
                .filter(entity -> entity.getRole() == WorkflowArtifactRole.PRINT_PREVIEW)
                .findFirst()
                .map(this::printPreviewMetadata)
                .orElse(null);
        Instant expiresAt = entities.stream()
                .map(WorkflowArtifactEntity::getExpiresAt)
                .min(Instant::compareTo)
                .orElse(null);
        return new WorkflowArtifactGroupResponse(exportId, downloads,
                downloads.isEmpty() ? null : downloads.get(0), preview, expiresAt);
    }

    private ReportArtifactMetadata metadata(WorkflowArtifactEntity entity) {
        return new ReportArtifactMetadata(entity.getArtifactId(), entity.getFormat(), entity.getFileName(),
                entity.getMimeType(), entity.getSizeBytes(), entity.getSha256(), entity.getExpiresAt(),
                "/api/workflow-artifacts/" + entity.getArtifactId() + "/content");
    }

    private ReportPrintPreviewMetadata printPreviewMetadata(WorkflowArtifactEntity entity) {
        return new ReportPrintPreviewMetadata(entity.getArtifactId(),
                "/api/workflow-artifacts/" + entity.getArtifactId() + "/content");
    }
}
