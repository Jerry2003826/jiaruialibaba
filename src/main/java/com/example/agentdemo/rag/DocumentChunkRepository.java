package com.example.agentdemo.rag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, Long> {

    List<DocumentChunkEntity> findByVectorIdIn(Collection<String> vectorIds);

    List<DocumentChunkEntity> findByDocumentIdOrderByChunkIndexAsc(Long documentId);

    @Modifying
    @Query("delete from DocumentChunkEntity c where c.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);

    Optional<DocumentChunkEntity> findByVectorId(String vectorId);
}
