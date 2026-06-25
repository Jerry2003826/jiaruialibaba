package com.example.agentdemo.trace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "run_steps", indexes = {
        @Index(name = "idx_run_steps_run_started_at", columnList = "runId, startedAt")
})
public class RunStepEntity {

    @Id
    @Column(length = 64)
    private String stepId;

    @Column(nullable = false, length = 64)
    private String runId;

    @Column(nullable = false, length = 128)
    private String nodeName;

    @Lob
    @Column(nullable = false)
    private String inputJson;

    @Lob
    private String outputJson;

    @Lob
    private String errorMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StepStatus status;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    protected RunStepEntity() {
    }

    public RunStepEntity(String stepId, String runId, String nodeName, String inputJson, StepStatus status,
            Instant startedAt) {
        this.stepId = stepId;
        this.runId = runId;
        this.nodeName = nodeName;
        this.inputJson = inputJson;
        this.status = status;
        this.startedAt = startedAt;
    }

    public String getStepId() {
        return stepId;
    }

    public String getRunId() {
        return runId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getInputJson() {
        return inputJson;
    }

    public String getOutputJson() {
        return outputJson;
    }

    public void setOutputJson(String outputJson) {
        this.outputJson = outputJson;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public StepStatus getStatus() {
        return status;
    }

    public void setStatus(StepStatus status) {
        this.status = status;
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
