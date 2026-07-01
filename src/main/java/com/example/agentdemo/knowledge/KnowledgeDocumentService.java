package com.example.agentdemo.knowledge;

import com.example.agentdemo.audit.Audited;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentPageResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentResponse;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentIndexingService;
import com.example.agentdemo.rag.DocumentManagementService;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeDocumentService {

    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final DocumentRepository documentRepository;
    private final DocumentIndexingService documentIndexingService;
    private final DocumentManagementService documentManagementService;
    private final KnowledgeResponseMapper knowledgeResponseMapper;

    public KnowledgeDocumentService(KnowledgeBaseAccessService knowledgeBaseAccessService,
            DocumentRepository documentRepository, DocumentIndexingService documentIndexingService,
            DocumentManagementService documentManagementService, KnowledgeResponseMapper knowledgeResponseMapper) {
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.documentRepository = documentRepository;
        this.documentIndexingService = documentIndexingService;
        this.documentManagementService = documentManagementService;
        this.knowledgeResponseMapper = knowledgeResponseMapper;
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentPageResponse listDocuments(String kbId, int page, int size) {
        knowledgeBaseAccessService.findKb(kbId);
        validatePage(page, size);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentEntity> result = documentRepository.findByOwnerIdAndKbIdAndIndexStatusNotOrderByCreatedAtDesc(
                SecurityIdentity.currentOwnerId(), kbId, DocumentIndexStatus.DELETED, pageable);
        return new KnowledgeDocumentPageResponse(
                result.getContent().stream().map(knowledgeResponseMapper::toKnowledgeDocumentResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentResponse getDocument(String kbId, Long documentId) {
        return knowledgeResponseMapper.toKnowledgeDocumentResponse(knowledgeBaseAccessService.findDocument(kbId, documentId));
    }

    @Transactional
    public void deleteDocument(String kbId, Long documentId) {
        knowledgeBaseAccessService.findDocument(kbId, documentId);
        documentManagementService.deleteDocument(documentId);
    }

    @Transactional
    @Audited(action = "document.reindex", resourceType = "document", resourceId = "#documentId")
    public KnowledgeDocumentResponse reindex(String kbId, Long documentId) {
        DocumentEntity document = knowledgeBaseAccessService.findDocument(kbId, documentId);
        document.markPending();
        try {
            documentIndexingService.index(document);
        }
        catch (RuntimeException ex) {
            document.markFailed(ex.getMessage());
        }
        return knowledgeResponseMapper.toKnowledgeDocumentResponse(documentRepository.save(document));
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new BusinessException("DOCUMENT_QUERY_INVALID", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("DOCUMENT_QUERY_INVALID", "size must be between 1 and 100");
        }
    }

}
