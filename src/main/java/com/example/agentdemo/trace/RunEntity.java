package com.example.agentdemo.trace;

import com.example.agentdemo.security.SecurityIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "runs", indexes = {
        @Index(name = "idx_runs_status_started_at", columnList = "status, startedAt"),
        @Index(name = "idx_runs_type_started_at", columnList = "type, startedAt")
})
public class RunEntity {

    @Id
    @Column(length = 64)
    private String runId;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunStatus status;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(nullable = false, length = Integer.MAX_VALUE)
    private String input;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = Integer.MAX_VALUE)
    private String output;

    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    @Column(length = Integer.MAX_VALUE)
    private String errorMessage;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    protected RunEntity() {
    }

    public RunEntity(String runId, RunType type, RunStatus status, String input, Instant startedAt) {
        this.runId = runId;
        this.ownerId = SecurityIdentity.currentOwnerId();
        this.type = type;
        this.status = status;
        this.input = input;
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

    public String getOwnerId() {
        return ownerId;
    }

    public RunType getType() {
        return type;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public String getInput() {
        return input;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }

}
