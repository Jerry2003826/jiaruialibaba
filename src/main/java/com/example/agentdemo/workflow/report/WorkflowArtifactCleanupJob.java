package com.example.agentdemo.workflow.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Component
public class WorkflowArtifactCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(WorkflowArtifactCleanupJob.class);

    private final WorkflowArtifactRepository repository;
    private final WorkflowArtifactStorage storage;
    private final Clock clock = Clock.systemUTC();

    public WorkflowArtifactCleanupJob(WorkflowArtifactRepository repository, WorkflowArtifactStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    @Scheduled(fixedDelayString = "${demo.workflow.artifacts.cleanup-interval-ms:3600000}")
    @Transactional
    public void deleteExpiredArtifacts() {
        for (WorkflowArtifactEntity entity : repository.findAllByExpiresAtBefore(clock.instant())) {
            try {
                storage.delete(entity.getStorageKey());
                repository.delete(entity);
            }
            catch (RuntimeException ex) {
                log.warn("Failed to delete expired workflow artifact {}", entity.getArtifactId(), ex);
            }
        }
    }
}
