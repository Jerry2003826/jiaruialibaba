package com.example.agentdemo.rag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, Long> {

    List<DocumentChunkEntity> findByVectorIdIn(Collection<String> vectorIds);

    Optional<DocumentChunkEntity> findByVectorId(String vectorId);
}
