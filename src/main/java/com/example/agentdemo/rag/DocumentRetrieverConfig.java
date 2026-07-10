package com.example.agentdemo.rag;

import com.example.agentdemo.config.AlibabaRuntimePolicy;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.knowledge.KnowledgeBaseRepository;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class DocumentRetrieverConfig {

    @Bean
    @Primary
    public DocumentRetriever documentRetriever(RagProperties ragProperties, VectorStoreGateway vectorStoreGateway,
            DocumentRepository documentRepository, DocumentChunkRepository documentChunkRepository,
            ObjectProvider<EmbeddingModel> embeddingModelProvider, KnowledgeBaseRepository knowledgeBaseRepository,
            KeywordDocumentRetriever keywordDocumentRetriever,
            AlibabaRuntimePolicy alibabaRuntimePolicy) {
        if ("dashvector".equalsIgnoreCase(ragProperties.getRag().getRetriever())
                && vectorStoreGateway.isConfigured()) {
            return new DashVectorDocumentRetriever(vectorStoreGateway, documentRepository, documentChunkRepository,
                    embeddingModelProvider, knowledgeBaseRepository);
        }
        if ("dashvector".equalsIgnoreCase(ragProperties.getRag().getRetriever())
                && alibabaRuntimePolicy.isAlibabaStackRequired()) {
            throw new IllegalStateException(
                    "DEMO_RAG_RETRIEVER=dashvector requires DashVector configuration when Alibaba stack is required "
                            + "(strict mode or demo.ai.fallback-enabled=false). Set DASHVECTOR_ENDPOINT and "
                            + "DASHVECTOR_API_KEY.");
        }
        return keywordDocumentRetriever;
    }

}
