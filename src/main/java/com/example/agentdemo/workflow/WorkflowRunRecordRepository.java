package com.example.agentdemo.workflow;

import com.example.agentdemo.trace.RunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRecordRepository extends JpaRepository<WorkflowRunRecordEntity, String> {

    boolean existsByDefinitionId(String definitionId);

    boolean existsByDefinitionIdAndOwnerId(String definitionId, String ownerId);

    java.util.Optional<WorkflowRunRecordEntity> findByRunIdAndOwnerId(String runId, String ownerId);

    @Query(value = """
            select record
            from WorkflowRunRecordEntity record
            where record.definitionId = :definitionId
              and record.ownerId = :ownerId
              and (:definitionVersion is null or record.definitionVersion = :definitionVersion)
              and (:status is null or exists (
                  select run.runId
                  from RunEntity run
                  where run.runId = record.runId
                    and run.ownerId = :ownerId
                    and run.status = :status
              ))
            """,
            countQuery = """
            select count(record)
            from WorkflowRunRecordEntity record
            where record.definitionId = :definitionId
              and record.ownerId = :ownerId
              and (:definitionVersion is null or record.definitionVersion = :definitionVersion)
              and (:status is null or exists (
                  select run.runId
                  from RunEntity run
                  where run.runId = record.runId
                    and run.ownerId = :ownerId
                    and run.status = :status
              ))
            """)
    Page<WorkflowRunRecordEntity> searchRuns(@Param("definitionId") String definitionId,
            @Param("ownerId") String ownerId, @Param("definitionVersion") Integer definitionVersion,
            @Param("status") RunStatus status, Pageable pageable);

}
