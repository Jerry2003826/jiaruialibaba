package com.example.agentdemo.knowledge;

import com.example.agentdemo.audit.Audited;
import com.example.agentdemo.common.PageRequestValidator;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentPageResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentResponse;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentIndexingService;
import com.example.agentdemo.rag.DocumentManagementService;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
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
    private final PageRequestValidator pageRequestValidator;

    @Autowired
    public KnowledgeDocumentService(KnowledgeBaseAccessService knowledgeBaseAccessService,
            DocumentRepository documentRepository, DocumentIndexingService documentIndexingService,
            DocumentManagementService documentManagementService, KnowledgeResponseMapper knowledgeResponseMapper,
            PageRequestValidator pageRequestValidator) {
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.documentRepository = documentRepository;
        this.documentIndexingService = documentIndexingService;
        this.documentManagementService = documentManagementService;
        this.knowledgeResponseMapper = knowledgeResponseMapper;
        this.pageRequestValidator = pageRequestValidator;
    }

    public KnowledgeDocumentService(KnowledgeBaseAccessService knowledgeBaseAccessService,
            DocumentRepository documentRepository, DocumentIndexingService documentIndexingService,
            DocumentManagementService documentManagementService, KnowledgeResponseMapper knowledgeResponseMapper) {
        this(knowledgeBaseAccessService, documentRepository, documentIndexingService, documentManagementService,
                knowledgeResponseMapper, new PageRequestValidator());
    }

    @Transactional(readOnly = true)
    public KnowledgeDocumentPageResponse listDocuments(String kbId, int page, int size) {
        knowledgeBaseAccessService.findKb(kbId);
        Pageable pageable = pageRequestValidator.build(page, size, "DOCUMENT_QUERY_INVALID",
                Sort.by(Sort.Direction.DESC, "createdAt"));
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

}
