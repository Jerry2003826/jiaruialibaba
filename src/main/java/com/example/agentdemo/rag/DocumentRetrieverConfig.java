package com.example.agentdemo.rag;

import com.example.agentdemo.config.RagProperties;
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
            ObjectProvider<EmbeddingModel> embeddingModelProvider, KeywordDocumentRetriever keywordDocumentRetriever) {
        if ("dashvector".equalsIgnoreCase(ragProperties.getRag().getRetriever())
                && vectorStoreGateway.isConfigured()) {
            return new DashVectorDocumentRetriever(vectorStoreGateway, documentRepository, documentChunkRepository,
                    embeddingModelProvider);
        }
        return keywordDocumentRetriever;
    }

}
