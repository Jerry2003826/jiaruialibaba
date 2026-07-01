package com.example.agentdemo.trace;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public interface RunStepRepository extends JpaRepository<RunStepEntity, String> {

    List<RunStepEntity> findByRunIdOrderByStartedAtAsc(String runId);

    List<RunStepEntity> findByOwnerIdAndRunIdOrderByStartedAtAsc(String ownerId, String runId);

    @Query("""
            select step
            from RunStepEntity step
            where step.ownerId = :ownerId
              and step.runId = :runId
              and (
                  :lastStartedAt is null
                  or step.startedAt > :lastStartedAt
                  or (
                      step.startedAt = :lastStartedAt
                      and (:lastStepId is null or step.stepId > :lastStepId)
                  )
              )
            order by step.startedAt asc, step.stepId asc
            """)
    List<RunStepEntity> findAfterCursor(@Param("ownerId") String ownerId, @Param("runId") String runId,
            @Param("lastStartedAt") Instant lastStartedAt, @Param("lastStepId") String lastStepId);

    long countByOwnerIdAndRunId(String ownerId, String runId);

    List<RunStepEntity> findByOwnerIdAndRunIdAndStepIdInOrderByStartedAtAscStepIdAsc(String ownerId, String runId,
            Set<String> stepIds);

}
