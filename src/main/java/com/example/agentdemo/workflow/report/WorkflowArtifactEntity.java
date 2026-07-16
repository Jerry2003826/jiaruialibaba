package com.example.agentdemo.workflow.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_artifacts", indexes = {
        @Index(name = "idx_workflow_artifacts_owner_run", columnList = "owner_id,run_id"),
        @Index(name = "idx_workflow_artifacts_expires", columnList = "expires_at")
})
public class WorkflowArtifactEntity {

    @Id
    @Column(name = "artifact_id", length = 64)
    private String artifactId;

    @Column(name = "export_id", nullable = false, length = 64)
    private String exportId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "app_id", length = 64)
    private String appId;

    @Column(name = "node_id", nullable = false, length = 128)
    private String nodeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private WorkflowArtifactRole role;

    @Column(nullable = false, length = 16)
    private String format;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "mime_type", nullable = false, length = 160)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(name = "storage_key", nullable = false, unique = true, length = 255)
    private String storageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected WorkflowArtifactEntity() {
    }

    WorkflowArtifactEntity(String artifactId, String exportId, String ownerId, String runId, String appId,
            String nodeId, WorkflowArtifactRole role, String format, String fileName, String mimeType,
            long sizeBytes, String sha256, String storageKey, Instant createdAt, Instant expiresAt) {
        this.artifactId = artifactId;
        this.exportId = exportId;
        this.ownerId = ownerId;
        this.runId = runId;
        this.appId = appId;
        this.nodeId = nodeId;
        this.role = role;
        this.format = format;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.storageKey = storageKey;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getArtifactId() { return artifactId; }
    public String getExportId() { return exportId; }
    public String getOwnerId() { return ownerId; }
    public String getRunId() { return runId; }
    public String getAppId() { return appId; }
    public String getNodeId() { return nodeId; }
    public WorkflowArtifactRole getRole() { return role; }
    public String getFormat() { return format; }
    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getStorageKey() { return storageKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
