package com.example.agentdemo.rag;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DocumentChunkPersistenceService {

    private final DocumentChunkRepository chunkRepository;

    public DocumentChunkPersistenceService(DocumentChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    @Transactional
    public List<DocumentChunkEntity> saveChunks(List<DocumentChunkEntity> chunkEntities) {
        return chunkRepository.saveAllAndFlush(chunkEntities);
    }

}
