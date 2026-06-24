package com.example.agentdemo.workflow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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

    @Column(nullable = false)
    private Integer definitionVersion;

    @Column(nullable = false)
    private Instant startedAt;

    protected WorkflowRunRecordEntity() {
    }

    public WorkflowRunRecordEntity(String runId, String definitionId, Integer definitionVersion, Instant startedAt) {
        this.runId = runId;
        this.definitionId = definitionId;
        this.definitionVersion = definitionVersion;
        this.startedAt = startedAt;
    }

    public String getRunId() {
        return runId;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public Integer getDefinitionVersion() {
        return definitionVersion;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

}
