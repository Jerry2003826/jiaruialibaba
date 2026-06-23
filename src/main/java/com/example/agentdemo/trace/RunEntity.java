package com.example.agentdemo.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "runs")
public class RunEntity {

    @Id
    @Column(length = 64)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunStatus status;

    @Lob
    @Column(nullable = false)
    private String input;

    @Lob
    private String output;

    @Lob
    private String errorMessage;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    protected RunEntity() {
    }

    public RunEntity(String runId, RunType type, RunStatus status, String input, Instant startedAt) {
        this.runId = runId;
        this.type = type;
        this.status = status;
        this.input = input;
        this.startedAt = startedAt;
    }

    public String getRunId() {
        return runId;
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
