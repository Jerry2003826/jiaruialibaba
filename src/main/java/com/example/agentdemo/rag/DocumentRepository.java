package com.example.agentdemo.rag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    Page<DocumentEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<DocumentEntity> findByIndexStatusNotOrderByCreatedAtDesc(DocumentIndexStatus indexStatus, Pageable pageable);

    Page<DocumentEntity> findByOwnerIdAndIndexStatusNotOrderByCreatedAtDesc(String ownerId,
            DocumentIndexStatus indexStatus, Pageable pageable);

    List<DocumentEntity> findByIndexStatus(DocumentIndexStatus indexStatus);

    List<DocumentEntity> findByIndexStatusNotIn(Collection<DocumentIndexStatus> indexStatuses);

    Page<DocumentEntity> findByIndexStatusNotIn(Collection<DocumentIndexStatus> indexStatuses, Pageable pageable);

    Page<DocumentEntity> findByOwnerIdAndIndexStatusNotIn(String ownerId,
            Collection<DocumentIndexStatus> indexStatuses, Pageable pageable);

    List<DocumentEntity> findByIdInAndIndexStatus(Collection<Long> ids, DocumentIndexStatus indexStatus);

    List<DocumentEntity> findByOwnerIdAndIdInAndIndexStatus(String ownerId, Collection<Long> ids,
            DocumentIndexStatus indexStatus);

    Optional<DocumentEntity> findByIdAndOwnerId(Long id, String ownerId);

    Optional<DocumentEntity> findByIdAndKbIdAndOwnerId(Long id, String kbId, String ownerId);

    Page<DocumentEntity> findByOwnerIdAndKbIdAndIndexStatusNotOrderByCreatedAtDesc(String ownerId, String kbId,
            DocumentIndexStatus indexStatus, Pageable pageable);

    Page<DocumentEntity> findByOwnerIdAndKbIdAndIndexStatusNotIn(String ownerId, String kbId,
            Collection<DocumentIndexStatus> indexStatuses, Pageable pageable);

    @Query("""
            select d.kbId as kbId, count(d) as count
            from DocumentEntity d
            where d.ownerId = :ownerId
              and d.kbId in :kbIds
            group by d.kbId
            """)
    List<KbDocumentCountProjection> countGroupedByOwnerIdAndKbIdIn(String ownerId, Collection<String> kbIds);

    long countByOwnerIdAndKbId(String ownerId, String kbId);

    boolean existsByTitleAndIndexStatusNot(String title, DocumentIndexStatus indexStatus);

    boolean existsByOwnerIdAndTitleAndIndexStatusNot(String ownerId, String title, DocumentIndexStatus indexStatus);

    long countByOwnerIdAndIndexStatusNot(String ownerId, DocumentIndexStatus indexStatus);

}
