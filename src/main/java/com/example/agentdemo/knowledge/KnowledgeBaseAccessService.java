package com.example.agentdemo.knowledge;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.stereotype.Service;

@Service
class KnowledgeBaseAccessService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;

    KnowledgeBaseAccessService(KnowledgeBaseRepository knowledgeBaseRepository, DocumentRepository documentRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
    }

    KnowledgeBaseEntity findKb(String kbId) {
        return knowledgeBaseRepository.findByKbIdAndOwnerIdAndSystemManagedFalse(kbId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND", "Knowledge base not found: " + kbId));
    }

    KnowledgeBaseEntity findManagedKb(String kbId, KnowledgeBasePurpose purpose) {
        return knowledgeBaseRepository.findByKbIdAndOwnerIdAndPurposeAndSystemManagedTrue(kbId,
                SecurityIdentity.currentOwnerId(), purpose)
                .orElseThrow(() -> new BusinessException("KNOWLEDGE_BASE_NOT_FOUND",
                        "Knowledge base not found: " + kbId));
    }

    DocumentEntity findDocument(String kbId, Long documentId) {
        if (documentId == null) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found");
        }
        findKb(kbId);
        DocumentEntity document = documentRepository
                .findByIdAndKbIdAndOwnerId(documentId, kbId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND",
                        "Document not found: " + documentId + " in kb " + kbId));
        if (document.getIndexStatus() == DocumentIndexStatus.DELETED) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found: " + documentId);
        }
        return document;
    }

}
