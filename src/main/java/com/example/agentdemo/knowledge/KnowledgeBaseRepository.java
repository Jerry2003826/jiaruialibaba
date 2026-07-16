package com.example.agentdemo.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link KnowledgeBaseEntity}, owner-scoped.
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    Optional<KnowledgeBaseEntity> findByKbIdAndOwnerId(String kbId, String ownerId);

    Optional<KnowledgeBaseEntity> findByKbIdAndOwnerIdAndSystemManagedFalse(String kbId, String ownerId);

    Optional<KnowledgeBaseEntity> findByKbIdAndOwnerIdAndPurposeAndSystemManagedTrue(String kbId, String ownerId,
            KnowledgeBasePurpose purpose);

    Optional<KnowledgeBaseEntity> findByOwnerIdAndPurposeAndSystemManagedTrue(String ownerId,
            KnowledgeBasePurpose purpose);

    List<KnowledgeBaseEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    List<KnowledgeBaseEntity> findByOwnerIdAndSystemManagedFalseOrderByCreatedAtDesc(String ownerId);

    boolean existsByKbIdAndOwnerId(String kbId, String ownerId);

}
