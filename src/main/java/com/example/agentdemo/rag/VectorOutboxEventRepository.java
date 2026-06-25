package com.example.agentdemo.rag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface VectorOutboxEventRepository extends JpaRepository<VectorOutboxEventEntity, Long> {

    List<VectorOutboxEventEntity> findTop50ByStatusInAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            Collection<VectorOutboxEventStatus> statuses, Instant now);

}
