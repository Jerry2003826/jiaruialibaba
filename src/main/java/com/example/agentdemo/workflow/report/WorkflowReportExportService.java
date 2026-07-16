package com.example.agentdemo.workflow.report;

import com.example.agentdemo.common.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class WorkflowReportExportService {

    private final ReportDocumentRenderer renderer;
    private final WorkflowArtifactRepository repository;
    private final WorkflowArtifactStorage storage;
    private final WorkflowArtifactProperties properties;
    private final Clock clock;

    @Autowired
    public WorkflowReportExportService(ReportDocumentRenderer renderer, WorkflowArtifactRepository repository,
            WorkflowArtifactStorage storage, WorkflowArtifactProperties properties) {
        this(renderer, repository, storage, properties, Clock.systemUTC());
    }

    WorkflowReportExportService(ReportDocumentRenderer renderer, WorkflowArtifactRepository repository,
            WorkflowArtifactStorage storage, WorkflowArtifactProperties properties, Clock clock) {
        this.renderer = renderer;
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ReportExportResult export(ReportExportCommand command) {
        validate(command);
        ReportRenderBundle rendered = renderer.render(command.renderRequest(), command.formats());
        enforceSizeLimits(rendered);

        Instant createdAt = clock.instant();
        Instant expiresAt = createdAt.plus(command.retentionDays(), ChronoUnit.DAYS);
        String exportId = id("exp_");
        String baseName = safeBaseName(command.fileName());
        List<WorkflowArtifactEntity> entities = new ArrayList<>();
        List<ReportArtifactMetadata> artifacts = new ArrayList<>();
        List<String> storedKeys = new ArrayList<>();
        try {
            for (ReportFormat format : command.formats()) {
                byte[] bytes = rendered.files().get(format);
                WorkflowArtifactEntity entity = entity(command, exportId, baseName + "." + format.extension(),
                        format.name().toLowerCase(Locale.ROOT), format.mimeType(), WorkflowArtifactRole.DOWNLOAD,
                        bytes, createdAt, expiresAt);
                storage.store(entity.getStorageKey(), bytes);
                storedKeys.add(entity.getStorageKey());
                entities.add(entity);
                artifacts.add(metadata(entity));
            }
            WorkflowArtifactEntity preview = entity(command, exportId, baseName + "-print.html", "html",
                    ReportFormat.HTML.mimeType(), WorkflowArtifactRole.PRINT_PREVIEW, rendered.printPreview(),
                    createdAt, expiresAt);
            storage.store(preview.getStorageKey(), rendered.printPreview());
            storedKeys.add(preview.getStorageKey());
            entities.add(preview);
            repository.saveAll(entities);
            return new ReportExportResult(exportId, List.copyOf(artifacts), artifacts.getFirst(),
                    printPreviewMetadata(preview), expiresAt);
        }
        catch (RuntimeException ex) {
            storedKeys.forEach(key -> {
                try { storage.delete(key); } catch (RuntimeException ignored) { }
            });
            if (ex instanceof BusinessException) throw ex;
            throw new BusinessException("REPORT_EXPORT_FAILED", "Failed to persist report artifacts", ex);
        }
    }

    private void validate(ReportExportCommand command) {
        if (command.renderRequest().markdown().length() > properties.getMaxSourceChars()) {
            throw new BusinessException("REPORT_EXPORT_LIMIT_EXCEEDED", "Report content exceeds character limit");
        }
        if (command.formats() == null || command.formats().isEmpty() || command.formats().size() > 5) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED", "Report formats must contain one to five values");
        }
        if (command.retentionDays() < 1 || command.retentionDays() > properties.getMaxRetentionDays()) {
            throw new BusinessException("WORKFLOW_VALIDATION_FAILED", "Report retentionDays is out of range");
        }
    }

    private void enforceSizeLimits(ReportRenderBundle rendered) {
        long total = rendered.printPreview().length;
        for (byte[] bytes : rendered.files().values()) {
            if (bytes.length > properties.getMaxFileBytes()) {
                throw new BusinessException("REPORT_EXPORT_LIMIT_EXCEEDED", "A report artifact exceeds file limit");
            }
            total += bytes.length;
        }
        if (total > properties.getMaxBatchBytes()) {
            throw new BusinessException("REPORT_EXPORT_LIMIT_EXCEEDED", "Report artifact batch exceeds size limit");
        }
    }

    private WorkflowArtifactEntity entity(ReportExportCommand command, String exportId, String fileName,
            String format, String mimeType, WorkflowArtifactRole role, byte[] bytes, Instant createdAt,
            Instant expiresAt) {
        String artifactId = id("art_");
        String extension = role == WorkflowArtifactRole.PRINT_PREVIEW ? "html" : formatExtension(format);
        String storageKey = exportId + "/" + artifactId + "." + extension;
        return new WorkflowArtifactEntity(artifactId, exportId, command.ownerId(), command.runId(),
                command.originAppId(), command.nodeId(), role, format, fileName, mimeType, bytes.length,
                sha256(bytes), storageKey, createdAt, expiresAt);
    }

    private String formatExtension(String format) {
        return "markdown".equals(format) ? "md" : format;
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

    static String safeBaseName(String configured) {
        String candidate = StringUtils.hasText(configured) ? configured.trim() : "report";
        candidate = candidate.replace('\\', '/');
        candidate = candidate.substring(candidate.lastIndexOf('/') + 1);
        candidate = candidate.replaceAll(
                "(?i)\\.(pdf|docx|html?|md|markdown|txt|exe|com|bat|cmd|sh|ps1|js|jar|msi|app|dmg|pkg)$", "");
        candidate = candidate.replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "-")
                .replaceAll("(?:^[. ]+|[. ]+$)", "")
                .replaceAll("\\s+", " ");
        if (!StringUtils.hasText(candidate)) candidate = "report";
        return candidate.length() > 120 ? candidate.substring(0, 120).trim() : candidate;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private String id(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
