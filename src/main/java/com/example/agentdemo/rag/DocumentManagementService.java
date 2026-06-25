package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.rag.dto.DocumentDetailResponse;
import com.example.agentdemo.rag.dto.DocumentPageResponse;
import com.example.agentdemo.rag.dto.DocumentSummaryResponse;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DocumentManagementService {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final VectorStoreGateway vectorStoreGateway;
    private final AlibabaRuntimePolicy alibabaRuntimePolicy;

    public DocumentManagementService(DocumentRepository documentRepository,
            DocumentChunkRepository documentChunkRepository, VectorStoreGateway vectorStoreGateway,
            AlibabaRuntimePolicy alibabaRuntimePolicy) {
        this.documentRepository = documentRepository;
        this.documentChunkRepository = documentChunkRepository;
        this.vectorStoreGateway = vectorStoreGateway;
        this.alibabaRuntimePolicy = alibabaRuntimePolicy;
    }

    @Transactional(readOnly = true)
    public DocumentPageResponse listDocuments(int page, int size) {
        validatePageRequest(page, size);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DocumentEntity> documentPage = documentRepository.findAllByOrderByCreatedAtDesc(pageable);
        List<DocumentSummaryResponse> content = documentPage.getContent().stream()
                .map(this::toSummary)
                .toList();
        return new DocumentPageResponse(content, documentPage.getNumber(), documentPage.getSize(),
                documentPage.getTotalElements(), documentPage.getTotalPages());
    }

    @Transactional(readOnly = true)
    public DocumentDetailResponse getDocument(Long documentId) {
        return toDetail(findDocument(documentId));
    }

    @Transactional
    public void deleteDocument(Long documentId) {
        DocumentEntity document = findDocument(documentId);
        List<DocumentChunkEntity> chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        List<String> vectorIds = chunks.stream().map(DocumentChunkEntity::getVectorId).toList();
        deleteVectorsBeforeDb(vectorIds);
        if (!chunks.isEmpty()) {
            documentChunkRepository.deleteByDocumentId(documentId);
        }
        documentRepository.delete(document);
    }

    private void deleteVectorsBeforeDb(List<String> vectorIds) {
        if (vectorIds.isEmpty()) {
            return;
        }
        if (!vectorStoreGateway.isConfigured()) {
            if (alibabaRuntimePolicy.isAlibabaStackRequired()) {
                throw new BusinessException("ALIBABA_VECTOR_STORE_NOT_CONFIGURED",
                        "DashVector is required but is not configured");
            }
            return;
        }
        vectorStoreGateway.delete(vectorIds);
    }

    private DocumentEntity findDocument(Long documentId) {
        if (documentId == null) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found");
        }
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new BusinessException("DOCUMENT_NOT_FOUND", "Document not found: " + documentId));
    }

    private void validatePageRequest(int page, int size) {
        if (page < 0) {
            throw new BusinessException("DOCUMENT_QUERY_INVALID", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("DOCUMENT_QUERY_INVALID", "size must be between 1 and 100");
        }
    }

    private DocumentSummaryResponse toSummary(DocumentEntity document) {
        return new DocumentSummaryResponse(document.getId(), document.getTitle(), document.getContent().length(),
                document.getCreatedAt());
    }

    private DocumentDetailResponse toDetail(DocumentEntity document) {
        return new DocumentDetailResponse(document.getId(), document.getTitle(), document.getContent(),
                document.getCreatedAt());
    }

}
