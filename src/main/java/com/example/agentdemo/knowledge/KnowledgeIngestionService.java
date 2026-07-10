package com.example.agentdemo.knowledge;

import com.example.agentdemo.audit.Audited;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentResponse;
import com.example.agentdemo.knowledge.dto.TextDocumentRequest;
import com.example.agentdemo.rag.DocumentEntity;
import com.example.agentdemo.rag.DocumentIndexingService;
import com.example.agentdemo.rag.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final DocumentRepository documentRepository;
    private final DocumentIndexingService documentIndexingService;
    private final DocumentTextExtractor documentTextExtractor;
    private final KnowledgeProperties knowledgeProperties;
    private final KnowledgeResponseMapper knowledgeResponseMapper;

    public KnowledgeIngestionService(KnowledgeBaseAccessService knowledgeBaseAccessService,
            DocumentRepository documentRepository, DocumentIndexingService documentIndexingService,
            DocumentTextExtractor documentTextExtractor, KnowledgeProperties knowledgeProperties,
            KnowledgeResponseMapper knowledgeResponseMapper) {
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.documentRepository = documentRepository;
        this.documentIndexingService = documentIndexingService;
        this.documentTextExtractor = documentTextExtractor;
        this.knowledgeProperties = knowledgeProperties;
        this.knowledgeResponseMapper = knowledgeResponseMapper;
    }

    @Transactional
    @Audited(action = "document.create", resourceType = "document",
            resourceId = "#result == null ? null : #result.documentId()")
    public KnowledgeDocumentResponse addTextDocument(String kbId, TextDocumentRequest request) {
        KnowledgeBaseEntity kb = knowledgeBaseAccessService.findKb(kbId);
        return addTextDocument(kb, request.title(), request.content());
    }

    /**
     * Internal-only system-managed ingestion path for the workflow builder guidance corpus.
     */
    @Transactional
    public KnowledgeDocumentResponse addManagedTextDocument(String kbId, String title, String content) {
        KnowledgeBaseEntity kb = knowledgeBaseAccessService.findManagedKb(kbId, KnowledgeBasePurpose.WORKFLOW_BUILDER);
        return addTextDocument(kb, title, content, DocumentEntity.WORKFLOW_BUILDER_SOURCE_TYPE);
    }

    private KnowledgeDocumentResponse addTextDocument(KnowledgeBaseEntity kb, String title, String content) {
        return addTextDocument(kb, title, content, "TEXT");
    }

    private KnowledgeDocumentResponse addTextDocument(KnowledgeBaseEntity kb, String title, String content,
            String sourceType) {
        if (content.length() > knowledgeProperties.getMaxContentChars()) {
            throw new BusinessException("DOCUMENT_CONTENT_TOO_LARGE",
                    "Document content exceeds the maximum size of " + knowledgeProperties.getMaxContentChars()
                            + " characters");
        }
        String resolvedTitle = StringUtils.hasText(title) ? title.trim() : "Untitled";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return ingest(kb.getKbId(), resolvedTitle, content, sourceType, null, "text/plain", (long) bytes.length,
                bytes);
    }

    @Transactional
    @Audited(action = "document.create", resourceType = "document",
            resourceId = "#result == null ? null : #result.documentId()")
    public KnowledgeDocumentResponse addFileDocument(String kbId, MultipartFile file) {
        knowledgeBaseAccessService.findKb(kbId);
        if (file == null || file.isEmpty()) {
            throw new BusinessException("DOCUMENT_FILE_EMPTY", "Uploaded file is empty");
        }
        if (file.getSize() > knowledgeProperties.getMaxFileBytes()) {
            throw new BusinessException("DOCUMENT_FILE_TOO_LARGE",
                    "File exceeds the maximum size of " + knowledgeProperties.getMaxFileBytes() + " bytes");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        }
        catch (IOException ex) {
            throw new BusinessException("DOCUMENT_FILE_READ_FAILED", "Failed to read uploaded file", ex);
        }
        String fileName = documentTextExtractor.sanitizeFileName(file.getOriginalFilename());
        String mimeType = documentTextExtractor.detectMimeType(bytes, fileName);
        if (!documentTextExtractor.isAllowedMimeType(mimeType)) {
            throw new BusinessException("DOCUMENT_MIME_NOT_ALLOWED",
                    "Uploaded file type is not allowed: " + (StringUtils.hasText(mimeType) ? mimeType : "unknown"));
        }
        String title = StringUtils.hasText(fileName) ? fileName : "Uploaded file";
        try {
            String content = documentTextExtractor.extractText(bytes, fileName);
            return ingest(kbId, title, content, "FILE", fileName, mimeType, (long) bytes.length, bytes);
        }
        catch (BusinessException ex) {
            DocumentEntity failed = new DocumentEntity(title, "");
            failed.assignKnowledge(kbId, "FILE", fileName, mimeType, (long) bytes.length, sha256(bytes));
            failed.markFailed(ex.getMessage());
            return knowledgeResponseMapper.toKnowledgeDocumentResponse(documentRepository.save(failed));
        }
    }

    private KnowledgeDocumentResponse ingest(String kbId, String title, String content, String sourceType,
            String fileName, String mimeType, Long sizeBytes, byte[] contentBytes) {
        DocumentEntity document = new DocumentEntity(title, content);
        document.assignKnowledge(kbId, sourceType, fileName, mimeType, sizeBytes, sha256(contentBytes));
        // Builder documents have a partial unique index on their stable rule identity. Flush
        // before embedding/indexing so a cross-instance loser fails without doing external work.
        DocumentEntity saved = DocumentEntity.WORKFLOW_BUILDER_SOURCE_TYPE.equals(sourceType)
                ? documentRepository.saveAndFlush(document)
                : documentRepository.save(document);
        if (document.isWorkflowBuilderManaged()) {
            // Builder guidance is retrieved through the hidden KB keyword path. Keeping it out of
            // the shared vector collection prevents internal rules from consuming public RAG top-k.
            saved.markReady();
            return knowledgeResponseMapper.toKnowledgeDocumentResponse(saved);
        }
        try {
            documentIndexingService.index(saved);
        }
        catch (RuntimeException ex) {
            log.warn("Indexing failed for kb {} document {}", kbId, saved.getId(), ex);
            saved.markFailed(ex.getMessage());
            saved = documentRepository.save(saved);
        }
        return knowledgeResponseMapper.toKnowledgeDocumentResponse(saved);
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

}
