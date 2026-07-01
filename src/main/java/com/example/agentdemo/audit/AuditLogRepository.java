package com.example.agentdemo.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for {@link AuditLogEntity}. Queries are always owner-scoped.
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    Page<AuditLogEntity> findByOwnerIdOrderByCreatedAtDescIdDesc(String ownerId, Pageable pageable);

    Page<AuditLogEntity> findByOwnerIdAndResourceTypeOrderByCreatedAtDescIdDesc(String ownerId,
            String resourceType, Pageable pageable);

}
