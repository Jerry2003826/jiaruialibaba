package com.example.agentdemo.rag;

import com.example.agentdemo.rag.dto.DocumentRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentPersistenceService {

    private final DocumentRepository documentRepository;

    public DocumentPersistenceService(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional
    public DocumentEntity save(DocumentRequest request) {
        return documentRepository.save(new DocumentEntity(request.title(), request.content()));
    }

}
