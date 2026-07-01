package com.example.agentdemo.usage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for {@link UsageRecordEntity}, owner-scoped for reads.
 */
public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, Long> {

    List<UsageRecordEntity> findByRunIdAndOwnerId(String runId, String ownerId);

}
