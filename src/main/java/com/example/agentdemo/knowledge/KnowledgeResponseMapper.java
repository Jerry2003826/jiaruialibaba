package com.example.agentdemo.knowledge;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.knowledge.dto.KnowledgeBaseResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentResponse;
import com.example.agentdemo.rag.DocumentEntity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class KnowledgeResponseMapper {

    private final ObjectMapper objectMapper;

    KnowledgeResponseMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    KnowledgeBaseResponse toKnowledgeBaseResponse(KnowledgeBaseEntity entity, long documentCount) {
        return new KnowledgeBaseResponse(entity.getKbId(), entity.getName(), entity.getDescription(),
                retrievalConfig(entity), documentCount, entity.getCreatedAt(), entity.getUpdatedAt());
    }

    KnowledgeDocumentResponse toKnowledgeDocumentResponse(DocumentEntity document) {
        return new KnowledgeDocumentResponse(document.getId(), document.getKbId(), document.getTitle(),
                document.getSourceType(), document.getFileName(), document.getMimeType(), document.getSizeBytes(),
                document.getContent() == null ? 0 : document.getContent().length(), document.getIndexStatus(),
                document.getErrorMessage(), document.getCreatedAt());
    }

    RetrievalConfig retrievalConfig(KnowledgeBaseEntity kb) {
        if (!StringUtils.hasText(kb.getRetrievalConfigJson())) {
            return RetrievalConfig.defaults();
        }
        try {
            return objectMapper.readValue(kb.getRetrievalConfigJson(), RetrievalConfig.class);
        }
        catch (JsonProcessingException ex) {
            return RetrievalConfig.defaults();
        }
    }

    String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new BusinessException("KNOWLEDGE_CONFIG_SERIALIZATION_FAILED",
                    "Failed to serialize retrieval config", ex);
        }
    }

}
