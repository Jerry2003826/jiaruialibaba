package com.example.agentdemo.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link KnowledgeBaseEntity}, owner-scoped.
 */
public interface KnowledgeBaseRepository extends JpaRepository<KnowledgeBaseEntity, Long> {

    Optional<KnowledgeBaseEntity> findByKbIdAndOwnerId(String kbId, String ownerId);

    List<KnowledgeBaseEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

    boolean existsByKbIdAndOwnerId(String kbId, String ownerId);

}
