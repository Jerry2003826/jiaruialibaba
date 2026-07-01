package com.example.agentdemo.knowledge;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.common.JsonPayloadCodec;
import com.example.agentdemo.knowledge.dto.KnowledgeBaseResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentResponse;
import com.example.agentdemo.rag.DocumentEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class KnowledgeResponseMapper {

    private final JsonPayloadCodec jsonPayloadCodec;

    @Autowired
    KnowledgeResponseMapper(JsonPayloadCodec jsonPayloadCodec) {
        this.jsonPayloadCodec = jsonPayloadCodec;
    }

    KnowledgeResponseMapper(ObjectMapper objectMapper) {
        this(new JsonPayloadCodec(objectMapper));
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
        RetrievalConfig config = jsonPayloadCodec.readOrNull(kb.getRetrievalConfigJson(), RetrievalConfig.class);
        return config == null ? RetrievalConfig.defaults() : config;
    }

    String toJson(Object value) {
        return jsonPayloadCodec.write(value, "KNOWLEDGE_CONFIG_SERIALIZATION_FAILED",
                "Failed to serialize retrieval config");
    }

}
