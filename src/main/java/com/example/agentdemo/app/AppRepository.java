package com.example.agentdemo.app;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for {@link AppEntity}, always owner-scoped.
 */
public interface AppRepository extends JpaRepository<AppEntity, Long> {

    Optional<AppEntity> findByAppIdAndOwnerId(String appId, String ownerId);

    Page<AppEntity> findByOwnerIdOrderByCreatedAtDescIdDesc(String ownerId, Pageable pageable);

    boolean existsByWorkflowDefinitionIdAndOwnerId(String workflowDefinitionId, String ownerId);

}
