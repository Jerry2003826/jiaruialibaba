package com.example.agentdemo.workflow.report;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkflowArtifactRepository extends JpaRepository<WorkflowArtifactEntity, String> {

    Optional<WorkflowArtifactEntity> findByArtifactIdAndOwnerId(String artifactId, String ownerId);

    List<WorkflowArtifactEntity> findAllByRunIdAndOwnerIdAndRoleOrderByCreatedAtAsc(
            String runId, String ownerId, WorkflowArtifactRole role);

    List<WorkflowArtifactEntity> findAllByRunIdAndOwnerIdOrderByCreatedAtAsc(String runId, String ownerId);

    List<WorkflowArtifactEntity> findAllByExpiresAtBefore(Instant cutoff);
}
