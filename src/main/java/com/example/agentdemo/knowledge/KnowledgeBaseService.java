package com.example.agentdemo.knowledge;

import com.example.agentdemo.audit.Audited;
import com.example.agentdemo.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.agentdemo.knowledge.dto.KnowledgeBaseResponse;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.rag.KbDocumentCountProjection;
import com.example.agentdemo.security.SecurityIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Knowledge base aggregate facade that only owns KB CRUD and grouped document counts.
 */
@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final KnowledgeResponseMapper knowledgeResponseMapper;

    public KnowledgeBaseService(KnowledgeBaseRepository knowledgeBaseRepository, DocumentRepository documentRepository,
            KnowledgeBaseAccessService knowledgeBaseAccessService, KnowledgeResponseMapper knowledgeResponseMapper,
            ObjectMapper objectMapper) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.knowledgeResponseMapper = knowledgeResponseMapper;
    }

    @Transactional
    @Audited(action = "kb.create", resourceType = "knowledge-base", resourceId = "#result.kbId()")
    public KnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        RetrievalConfig config = request.retrievalConfig() == null ? RetrievalConfig.defaults()
                : request.retrievalConfig();
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(newKbId(), request.name().trim(),
                normalize(request.description()), knowledgeResponseMapper.toJson(config));
        KnowledgeBaseEntity saved = knowledgeBaseRepository.save(entity);
        return knowledgeResponseMapper.toKnowledgeBaseResponse(saved, 0L);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeBaseResponse> listKnowledgeBases() {
        List<KnowledgeBaseEntity> entities = knowledgeBaseRepository
                .findByOwnerIdOrderByCreatedAtDesc(SecurityIdentity.currentOwnerId());
        if (entities.isEmpty()) {
            return List.of();
        }
        List<String> kbIds = entities.stream().map(KnowledgeBaseEntity::getKbId).toList();
        Map<String, Long> countsByKbId = documentRepository
                .countGroupedByOwnerIdAndKbIdIn(SecurityIdentity.currentOwnerId(), kbIds)
                .stream()
                .collect(Collectors.toMap(KbDocumentCountProjection::getKbId, KbDocumentCountProjection::getCount,
                        (left, right) -> right));
        return entities.stream()
                .map(entity -> knowledgeResponseMapper.toKnowledgeBaseResponse(entity,
                        countsByKbId.getOrDefault(entity.getKbId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeBaseResponse getKnowledgeBase(String kbId) {
        KnowledgeBaseEntity entity = knowledgeBaseAccessService.findKb(kbId);
        long count = documentRepository.countByOwnerIdAndKbId(entity.getOwnerId(), entity.getKbId());
        return knowledgeResponseMapper.toKnowledgeBaseResponse(entity, count);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String newKbId() {
        return "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

}
