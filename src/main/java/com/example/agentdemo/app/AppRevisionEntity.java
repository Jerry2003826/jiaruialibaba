package com.example.agentdemo.app;

import com.example.agentdemo.security.SecurityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.util.StringUtils;

import java.time.Instant;

/**
 * Immutable per-version snapshot of an app. The published revision is what the runtime executes.
 */
@Entity
@Table(name = "app_revisions")
public class AppRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "app_id", nullable = false, length = 64)
    private String appId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(nullable = false)
    private Integer version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AppStatus status;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(name = "snapshot_json", nullable = false, length = Integer.MAX_VALUE)
    private String snapshotJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AppRevisionEntity() {
    }

    public AppRevisionEntity(String appId, Integer version, AppStatus status, String snapshotJson) {
        this.appId = appId;
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.version = version;
        this.status = status;
        this.snapshotJson = snapshotJson;
    }

    @PrePersist
    void prePersist() {
        if (!StringUtils.hasText(ownerId)) {
            ownerId = SecurityIdentity.DEFAULT_OWNER_ID;
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void markPublished() {
        this.status = AppStatus.PUBLISHED;
    }

    /**
     * Finalizes this revision as the published, immutable snapshot. Called once when the draft
     * version transitions to PUBLISHED so the pinned workflow version is baked into the snapshot.
     *
     * @param snapshotJson the finalized snapshot JSON
     */
    public void publishSnapshot(String snapshotJson) {
        this.snapshotJson = snapshotJson;
        this.status = AppStatus.PUBLISHED;
    }

    public Long getId() {
        return id;
    }

    public String getAppId() {
        return appId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Integer getVersion() {
        return version;
    }

    public AppStatus getStatus() {
        return status;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

}
