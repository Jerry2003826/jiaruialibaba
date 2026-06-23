package com.example.agentdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "demo")
public class RagProperties {

    private final Rag rag = new Rag();

    private final Dashvector dashvector = new Dashvector();

    private final Ai ai = new Ai();

    public Rag getRag() {
        return rag;
    }

    public Dashvector getDashvector() {
        return dashvector;
    }

    public Ai getAi() {
        return ai;
    }

    public void validate() {
        require(rag.getChunkSize() > 0, "demo.rag.chunk-size must be greater than 0");
        require(rag.getChunkOverlap() >= 0, "demo.rag.chunk-overlap must be greater than or equal to 0");
        require(rag.getChunkOverlap() < rag.getChunkSize(), "demo.rag.chunk-overlap must be less than demo.rag.chunk-size");
        require(rag.getTopK() > 0, "demo.rag.top-k must be greater than 0");
        require(dashvector.getDimension() > 0, "demo.dashvector.dimension must be greater than 0");
        require(ai.getEmbeddingDimension() > 0, "demo.ai.embedding-dimension must be greater than 0");
        require(dashvector.getDimension() == ai.getEmbeddingDimension(),
                "demo.dashvector.dimension must equal demo.ai.embedding-dimension");
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }

    public static class Rag {

        private String retriever = "dashvector";

        private boolean keywordFallbackEnabled = true;

        private int chunkSize = 800;

        private int chunkOverlap = 120;

        private int topK = 5;

        public String getRetriever() {
            return retriever;
        }

        public void setRetriever(String retriever) {
            this.retriever = retriever;
        }

        public boolean isKeywordFallbackEnabled() {
            return keywordFallbackEnabled;
        }

        public void setKeywordFallbackEnabled(boolean keywordFallbackEnabled) {
            this.keywordFallbackEnabled = keywordFallbackEnabled;
        }

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getChunkOverlap() {
            return chunkOverlap;
        }

        public void setChunkOverlap(int chunkOverlap) {
            this.chunkOverlap = chunkOverlap;
        }

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

    }

    public static class Dashvector {

        private String endpoint = "";

        private String apiKey = "";

        private String collection = "agent_rag_docs";

        private int dimension = 1024;

        private String metric = "cosine";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getCollection() {
            return collection;
        }

        public void setCollection(String collection) {
            this.collection = collection;
        }

        public int getDimension() {
            return dimension;
        }

        public void setDimension(int dimension) {
            this.dimension = dimension;
        }

        public String getMetric() {
            return metric;
        }

        public void setMetric(String metric) {
            this.metric = metric;
        }

        public boolean isConfigured() {
            return StringUtils.hasText(endpoint) && StringUtils.hasText(apiKey);
        }

    }

    public static class Ai {

        private String embeddingModel = "text-embedding-v4";

        private int embeddingDimension = 1024;

        private String embeddingBaseUrl = "";

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingDimension() {
            return embeddingDimension;
        }

        public void setEmbeddingDimension(int embeddingDimension) {
            this.embeddingDimension = embeddingDimension;
        }

        public String getEmbeddingBaseUrl() {
            return embeddingBaseUrl;
        }

        public void setEmbeddingBaseUrl(String embeddingBaseUrl) {
            this.embeddingBaseUrl = embeddingBaseUrl;
        }

    }

}
