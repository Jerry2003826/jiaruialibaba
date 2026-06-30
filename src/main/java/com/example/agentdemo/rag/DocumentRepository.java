package com.example.agentdemo.rag;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    Page<DocumentEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<DocumentEntity> findByIndexStatusNotOrderByCreatedAtDesc(DocumentIndexStatus indexStatus, Pageable pageable);

    List<DocumentEntity> findByIndexStatus(DocumentIndexStatus indexStatus);

    List<DocumentEntity> findByIndexStatusNotIn(Collection<DocumentIndexStatus> indexStatuses);

    List<DocumentEntity> findByIdInAndIndexStatus(Collection<Long> ids, DocumentIndexStatus indexStatus);

    boolean existsByTitleAndIndexStatusNot(String title, DocumentIndexStatus indexStatus);

}
