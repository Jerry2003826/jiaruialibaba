package com.example.agentdemo.workflow;

import com.example.agentdemo.security.SecurityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "workflow_run_records")
public class WorkflowRunRecordEntity {

    @Id
    @Column(length = 64)
    private String runId;

    @Column(nullable = false, length = 64)
    private String definitionId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(nullable = false)
    private Integer definitionVersion;

    @Column(nullable = false)
    private Instant startedAt;

    protected WorkflowRunRecordEntity() {
    }

    public WorkflowRunRecordEntity(String runId, String definitionId, Integer definitionVersion, Instant startedAt) {
        this.runId = runId;
        this.definitionId = definitionId;
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.definitionVersion = definitionVersion;
        this.startedAt = startedAt;
    }

    @PrePersist
    void prePersist() {
        if (!org.springframework.util.StringUtils.hasText(ownerId)) {
            ownerId = SecurityIdentity.DEFAULT_OWNER_ID;
        }
    }

    public String getRunId() {
        return runId;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public Integer getDefinitionVersion() {
        return definitionVersion;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

}
