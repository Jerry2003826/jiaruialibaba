# DashVector RAG Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace keyword-only RAG with DashScope embeddings plus Alibaba Cloud DashVector vector retrieval, while preserving the current API and keyword fallback.

**Architecture:** Keep `RagController` thin and make `RagService` orchestrate document persistence, indexing, retrieval, model generation, and trace. Add a project-level `VectorStoreGateway` so DashVector is the first provider but not hardwired into the RAG service. Store source documents and chunk metadata in H2; store vectors and searchable metadata in DashVector.

**Tech Stack:** Java 21, Spring Boot 3.5.7, Spring AI 1.1.2, Spring AI Alibaba 1.1.2.2, DashScope `EmbeddingModel`, Alibaba `dashvector-java-sdk:1.0.18`, JPA/H2, JUnit 5.

---

## File Structure

- Modify `pom.xml`: add DashVector Java SDK dependency.
- Modify `src/main/resources/application.yml`: add `demo.rag`, `demo.dashvector`, and `demo.ai.embedding-model` config.
- Modify `.env.example`: add placeholder DashVector and embedding env vars.
- Modify `README.md`: document DashVector setup, env vars, and RAG verification curl commands.
- Modify `src/main/java/com/example/agentdemo/config/AiConfig.java`: add conditional `EmbeddingModel` bean using DashScope.
- Create `src/main/java/com/example/agentdemo/config/RagConfig.java`: enable typed RAG properties.
- Create `src/main/java/com/example/agentdemo/config/RagProperties.java`: hold chunking, fallback, DashVector, and embedding settings.
- Create `src/main/java/com/example/agentdemo/rag/DocumentChunkEntity.java`: JPA row for each chunk.
- Create `src/main/java/com/example/agentdemo/rag/DocumentChunkRepository.java`: chunk metadata access.
- Create `src/main/java/com/example/agentdemo/rag/TextChunker.java`: deterministic chunk splitter.
- Create `src/main/java/com/example/agentdemo/rag/vector/VectorDocument.java`: vector upsert DTO.
- Create `src/main/java/com/example/agentdemo/rag/vector/VectorSearchResult.java`: vector search DTO.
- Create `src/main/java/com/example/agentdemo/rag/vector/VectorStoreGateway.java`: provider-neutral vector API.
- Create `src/main/java/com/example/agentdemo/rag/vector/DashVectorGateway.java`: DashVector SDK adapter.
- Create `src/main/java/com/example/agentdemo/rag/DocumentIndexingService.java`: split, embed, upsert, persist chunks.
- Create `src/main/java/com/example/agentdemo/rag/DashVectorDocumentRetriever.java`: query embedding + DashVector search + H2 chunk resolution.
- Modify `src/main/java/com/example/agentdemo/rag/DocumentRetriever.java`: add `name()` so trace can identify active retriever.
- Modify `src/main/java/com/example/agentdemo/rag/KeywordDocumentRetriever.java`: implement `name()` and keep fallback behavior.
- Modify `src/main/java/com/example/agentdemo/rag/RagService.java`: call indexer on save, use active retriever name in trace, keep failure handling.
- Create tests under `src/test/java/com/example/agentdemo/rag/` for chunking, indexing, and retrieval fallback.

---

### Task 1: Dependencies And Configuration

**Files:**
- Modify: `pom.xml`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Create: `src/main/java/com/example/agentdemo/config/RagProperties.java`
- Create: `src/main/java/com/example/agentdemo/config/RagConfig.java`
- Modify: `src/main/java/com/example/agentdemo/config/AiConfig.java`

- [ ] **Step 1: Add DashVector dependency**

Add this dependency to `pom.xml` inside `<dependencies>`:

```xml
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>dashvector-java-sdk</artifactId>
    <version>1.0.18</version>
</dependency>
```

- [ ] **Step 2: Add application configuration**

Add these keys under the existing top-level `demo:` block in `src/main/resources/application.yml`:

```yaml
  rag:
    retriever: ${DEMO_RAG_RETRIEVER:dashvector}
    keyword-fallback-enabled: ${DEMO_RAG_KEYWORD_FALLBACK_ENABLED:true}
    chunk-size: ${DEMO_RAG_CHUNK_SIZE:800}
    chunk-overlap: ${DEMO_RAG_CHUNK_OVERLAP:120}
    top-k: ${DEMO_RAG_TOP_K:5}
  dashvector:
    endpoint: ${DASHVECTOR_ENDPOINT:}
    api-key: ${DASHVECTOR_API_KEY:}
    collection: ${DASHVECTOR_COLLECTION:agent_rag_docs}
    dimension: ${DASHVECTOR_DIMENSION:1024}
    metric: ${DASHVECTOR_METRIC:cosine}
```

Add this under the existing `demo.ai:` block:

```yaml
    embedding-model: ${AI_DASHSCOPE_EMBEDDING_MODEL:text-embedding-v4}
    embedding-dimension: ${AI_DASHSCOPE_EMBEDDING_DIMENSION:1024}
```

- [ ] **Step 3: Update `.env.example` with placeholders only**

Append:

```bash
AI_DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4
AI_DASHSCOPE_EMBEDDING_DIMENSION=1024

DASHVECTOR_ENDPOINT=
DASHVECTOR_API_KEY=
DASHVECTOR_COLLECTION=agent_rag_docs
DASHVECTOR_DIMENSION=1024
DASHVECTOR_METRIC=cosine

DEMO_RAG_RETRIEVER=dashvector
DEMO_RAG_KEYWORD_FALLBACK_ENABLED=true
DEMO_RAG_CHUNK_SIZE=800
DEMO_RAG_CHUNK_OVERLAP=120
DEMO_RAG_TOP_K=5
```

- [ ] **Step 4: Create typed properties**

Create `src/main/java/com/example/agentdemo/config/RagProperties.java`:

```java
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

    public static class Rag {
        private String retriever = "dashvector";
        private boolean keywordFallbackEnabled = true;
        private int chunkSize = 800;
        private int chunkOverlap = 120;
        private int topK = 5;

        public String getRetriever() { return retriever; }
        public void setRetriever(String retriever) { this.retriever = retriever; }
        public boolean isKeywordFallbackEnabled() { return keywordFallbackEnabled; }
        public void setKeywordFallbackEnabled(boolean keywordFallbackEnabled) { this.keywordFallbackEnabled = keywordFallbackEnabled; }
        public int getChunkSize() { return chunkSize; }
        public void setChunkSize(int chunkSize) { this.chunkSize = chunkSize; }
        public int getChunkOverlap() { return chunkOverlap; }
        public void setChunkOverlap(int chunkOverlap) { this.chunkOverlap = chunkOverlap; }
        public int getTopK() { return topK; }
        public void setTopK(int topK) { this.topK = topK; }
    }

    public static class Dashvector {
        private String endpoint = "";
        private String apiKey = "";
        private String collection = "agent_rag_docs";
        private int dimension = 1024;
        private String metric = "cosine";

        public boolean isConfigured() {
            return StringUtils.hasText(endpoint) && StringUtils.hasText(apiKey);
        }

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getCollection() { return collection; }
        public void setCollection(String collection) { this.collection = collection; }
        public int getDimension() { return dimension; }
        public void setDimension(int dimension) { this.dimension = dimension; }
        public String getMetric() { return metric; }
        public void setMetric(String metric) { this.metric = metric; }
    }

    public static class Ai {
        private String embeddingModel = "text-embedding-v4";
        private int embeddingDimension = 1024;

        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public int getEmbeddingDimension() { return embeddingDimension; }
        public void setEmbeddingDimension(int embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    }
}
```

- [ ] **Step 5: Enable properties**

Create `src/main/java/com/example/agentdemo/config/RagConfig.java`:

```java
package com.example.agentdemo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {
}
```

- [ ] **Step 6: Add DashScope EmbeddingModel bean**

Modify `src/main/java/com/example/agentdemo/config/AiConfig.java` imports:

```java
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
```

Add this bean after the existing `chatClient` bean:

```java
@Bean
@Conditional(DashScopeApiKeyPresentCondition.class)
@ConditionalOnMissingBean(EmbeddingModel.class)
public EmbeddingModel embeddingModel(Environment environment, RagProperties ragProperties) {
    String apiKey = environment.getRequiredProperty("spring.ai.dashscope.api-key");
    String baseUrl = normalizeBaseUrl(environment.getProperty("spring.ai.dashscope.base-url"));
    DashScopeApi.Builder dashScopeApiBuilder = DashScopeApi.builder().apiKey(apiKey);
    if (StringUtils.hasText(baseUrl)) {
        dashScopeApiBuilder.baseUrl(baseUrl);
    }
    DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
            .model(ragProperties.getAi().getEmbeddingModel())
            .dimensions(ragProperties.getAi().getEmbeddingDimension())
            .build();
    return DashScopeEmbeddingModel.builder()
            .dashScopeApi(dashScopeApiBuilder.build())
            .metadataMode(MetadataMode.NONE)
            .defaultOptions(options)
            .build();
}
```

- [ ] **Step 7: Build to catch dependency and config errors**

Run:

```bash
./mvnw clean package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add pom.xml src/main/resources/application.yml .env.example \
  src/main/java/com/example/agentdemo/config/AiConfig.java \
  src/main/java/com/example/agentdemo/config/RagConfig.java \
  src/main/java/com/example/agentdemo/config/RagProperties.java
git commit -m "Add DashVector RAG configuration"
```

---

### Task 2: Chunk Metadata And Text Splitting

**Files:**
- Create: `src/main/java/com/example/agentdemo/rag/DocumentChunkEntity.java`
- Create: `src/main/java/com/example/agentdemo/rag/DocumentChunkRepository.java`
- Create: `src/main/java/com/example/agentdemo/rag/TextChunker.java`
- Create: `src/test/java/com/example/agentdemo/rag/TextChunkerTest.java`

- [ ] **Step 1: Write chunker test**

Create `src/test/java/com/example/agentdemo/rag/TextChunkerTest.java`:

```java
package com.example.agentdemo.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextChunkerTest {

    @Test
    void returnsSingleChunkForShortText() {
        TextChunker chunker = new TextChunker(20, 5);
        assertThat(chunker.split("short text")).containsExactly("short text");
    }

    @Test
    void splitsLongTextWithOverlap() {
        TextChunker chunker = new TextChunker(10, 3);
        List<String> chunks = chunker.split("abcdefghijklmnopqrst");
        assertThat(chunks).containsExactly("abcdefghij", "hijklmnopq", "opqrst");
    }

    @Test
    void rejectsInvalidOverlap() {
        assertThatThrownBy(() -> new TextChunker(10, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chunkOverlap must be smaller than chunkSize");
    }
}
```

- [ ] **Step 2: Run failing test**

Run:

```bash
./mvnw -Dtest=TextChunkerTest test
```

Expected: FAIL because `TextChunker` does not exist.

- [ ] **Step 3: Add chunk entity**

Create `src/main/java/com/example/agentdemo/rag/DocumentChunkEntity.java`:

```java
package com.example.agentdemo.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "rag_document_chunks")
public class DocumentChunkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long documentId;

    @Column(nullable = false)
    private int chunkIndex;

    @Column(nullable = false, length = 128, unique = true)
    private String vectorId;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected DocumentChunkEntity() {
    }

    public DocumentChunkEntity(Long documentId, int chunkIndex, String vectorId, String content) {
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.vectorId = vectorId;
        this.content = content;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getDocumentId() { return documentId; }
    public int getChunkIndex() { return chunkIndex; }
    public String getVectorId() { return vectorId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
```

- [ ] **Step 4: Add chunk repository**

Create `src/main/java/com/example/agentdemo/rag/DocumentChunkRepository.java`:

```java
package com.example.agentdemo.rag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunkEntity, Long> {

    List<DocumentChunkEntity> findByVectorIdIn(Collection<String> vectorIds);

    Optional<DocumentChunkEntity> findByVectorId(String vectorId);
}
```

- [ ] **Step 5: Add chunker**

Create `src/main/java/com/example/agentdemo/rag/TextChunker.java`:

```java
package com.example.agentdemo.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class TextChunker {

    private final int chunkSize;
    private final int chunkOverlap;

    public TextChunker(int chunkSize, int chunkOverlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        if (chunkOverlap < 0) {
            throw new IllegalArgumentException("chunkOverlap must be zero or positive");
        }
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap must be smaller than chunkSize");
        }
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
    }

    public List<String> split(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        String normalized = content.trim();
        if (normalized.length() <= chunkSize) {
            return List.of(normalized);
        }
        List<String> chunks = new ArrayList<>();
        int step = chunkSize - chunkOverlap;
        for (int start = 0; start < normalized.length(); start += step) {
            int end = Math.min(start + chunkSize, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
        }
        return chunks;
    }
}
```

- [ ] **Step 6: Run test**

Run:

```bash
./mvnw -Dtest=TextChunkerTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/agentdemo/rag/DocumentChunkEntity.java \
  src/main/java/com/example/agentdemo/rag/DocumentChunkRepository.java \
  src/main/java/com/example/agentdemo/rag/TextChunker.java \
  src/test/java/com/example/agentdemo/rag/TextChunkerTest.java
git commit -m "Add RAG document chunk metadata"
```

---

### Task 3: Vector Gateway And DashVector Adapter

**Files:**
- Create: `src/main/java/com/example/agentdemo/rag/vector/VectorDocument.java`
- Create: `src/main/java/com/example/agentdemo/rag/vector/VectorSearchResult.java`
- Create: `src/main/java/com/example/agentdemo/rag/vector/VectorStoreGateway.java`
- Create: `src/main/java/com/example/agentdemo/rag/vector/DashVectorGateway.java`
- Create: `src/test/java/com/example/agentdemo/rag/vector/DashVectorGatewayTest.java`

- [ ] **Step 1: Add vector DTOs**

Create `src/main/java/com/example/agentdemo/rag/vector/VectorDocument.java`:

```java
package com.example.agentdemo.rag.vector;

import java.util.Map;

public record VectorDocument(String id, float[] vector, Map<String, Object> metadata) {
}
```

Create `src/main/java/com/example/agentdemo/rag/vector/VectorSearchResult.java`:

```java
package com.example.agentdemo.rag.vector;

import java.util.Map;

public record VectorSearchResult(String id, double score, Map<String, Object> metadata) {
}
```

- [ ] **Step 2: Add gateway interface**

Create `src/main/java/com/example/agentdemo/rag/vector/VectorStoreGateway.java`:

```java
package com.example.agentdemo.rag.vector;

import java.util.List;

public interface VectorStoreGateway {

    String name();

    boolean isConfigured();

    void ensureCollection();

    void upsert(List<VectorDocument> documents);

    List<VectorSearchResult> search(float[] queryVector, int topK);
}
```

- [ ] **Step 3: Add DashVector adapter**

Create `src/main/java/com/example/agentdemo/rag/vector/DashVectorGateway.java`:

```java
package com.example.agentdemo.rag.vector;

import com.aliyun.dashvector.DashVectorClient;
import com.aliyun.dashvector.DashVectorCollection;
import com.aliyun.dashvector.common.DashVectorException;
import com.aliyun.dashvector.models.Doc;
import com.aliyun.dashvector.models.Vector;
import com.aliyun.dashvector.models.requests.CreateCollectionRequest;
import com.aliyun.dashvector.models.requests.QueryDocRequest;
import com.aliyun.dashvector.models.requests.UpsertDocRequest;
import com.aliyun.dashvector.models.responses.Response;
import com.aliyun.dashvector.proto.CollectionInfo;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DashVectorGateway implements VectorStoreGateway {

    private final RagProperties.Dashvector properties;
    private final AtomicBoolean collectionChecked = new AtomicBoolean(false);

    public DashVectorGateway(RagProperties ragProperties) {
        this.properties = ragProperties.getDashvector();
    }

    @Override
    public String name() {
        return "DashVector";
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public void ensureCollection() {
        if (!isConfigured() || collectionChecked.get()) {
            return;
        }
        DashVectorClient client = null;
        try {
            client = client();
            Response<?> describe = client.describe(properties.getCollection());
            if (!describe.isSuccess()) {
                Response<Void> create = client.create(CreateCollectionRequest.builder()
                        .name(properties.getCollection())
                        .dimension(properties.getDimension())
                        .metric(metric())
                        .dataType(CollectionInfo.DataType.FLOAT)
                        .build());
                ensureSuccess(create, "create DashVector collection", "VECTOR_STORE_INDEX_FAILED");
            }
            collectionChecked.set(true);
        }
        catch (DashVectorException ex) {
            throw new BusinessException("VECTOR_STORE_INDEX_FAILED", "Failed to ensure DashVector collection", ex);
        }
        finally {
            close(client);
        }
    }

    @Override
    public void upsert(List<VectorDocument> documents) {
        if (documents.isEmpty()) {
            return;
        }
        ensureCollection();
        List<Doc> docs = documents.stream()
                .map(document -> Doc.builder()
                        .id(document.id())
                        .vector(toVector(document.vector()))
                        .fields(document.metadata())
                        .build())
                .toList();
        DashVectorClient client = null;
        try {
            client = client();
            DashVectorCollection collection = client.get(properties.getCollection());
            if (!collection.isSuccess()) {
                throw new BusinessException("VECTOR_STORE_INDEX_FAILED", collection.getMessage());
            }
            Response<?> response = collection.upsert(UpsertDocRequest.builder().docs(docs).build());
            ensureSuccess(response, "upsert DashVector documents", "VECTOR_STORE_INDEX_FAILED");
        }
        catch (DashVectorException ex) {
            throw new BusinessException("VECTOR_STORE_INDEX_FAILED", "Failed to upsert DashVector documents", ex);
        }
        finally {
            close(client);
        }
    }

    @Override
    public List<VectorSearchResult> search(float[] queryVector, int topK) {
        if (!isConfigured()) {
            throw new BusinessException("VECTOR_STORE_NOT_CONFIGURED", "DashVector is not configured");
        }
        ensureCollection();
        DashVectorClient client = null;
        try {
            client = client();
            DashVectorCollection collection = client.get(properties.getCollection());
            if (!collection.isSuccess()) {
                throw new BusinessException("VECTOR_STORE_SEARCH_FAILED", collection.getMessage());
            }
            Response<List<Doc>> response = collection.query(QueryDocRequest.builder()
                    .vector(toVector(queryVector))
                    .topk(topK)
                    .includeVector(false)
                    .build());
            ensureSuccess(response, "search DashVector documents", "VECTOR_STORE_SEARCH_FAILED");
            return response.getOutput().stream()
                    .map(doc -> new VectorSearchResult(doc.getId(), doc.getScore(), doc.getFields()))
                    .toList();
        }
        catch (DashVectorException ex) {
            throw new BusinessException("VECTOR_STORE_SEARCH_FAILED", "Failed to search DashVector documents", ex);
        }
        finally {
            close(client);
        }
    }

    private DashVectorClient client() throws DashVectorException {
        return new DashVectorClient(properties.getApiKey(), properties.getEndpoint());
    }

    private CollectionInfo.Metric metric() {
        return switch (properties.getMetric().toLowerCase()) {
            case "euclidean" -> CollectionInfo.Metric.euclidean;
            case "dotproduct" -> CollectionInfo.Metric.dotproduct;
            case "cosine" -> CollectionInfo.Metric.cosine;
            default -> throw new BusinessException("VECTOR_STORE_NOT_CONFIGURED",
                    "Unsupported DashVector metric: " + properties.getMetric());
        };
    }

    private Vector toVector(float[] values) {
        List<Float> floats = new ArrayList<>(values.length);
        for (float value : values) {
            floats.add(value);
        }
        return Vector.builder().value(floats).build();
    }

    private void close(DashVectorClient client) {
        if (client != null) {
            client.close();
        }
    }

    private void ensureSuccess(Response<?> response, String action, String errorCode) {
        if (!response.isSuccess()) {
            throw new BusinessException(errorCode, "Failed to " + action + ": " + response.getMessage());
        }
    }
}
```

- [ ] **Step 4: Compile the adapter**

Run:

```bash
./mvnw -DskipTests compile
```

Expected: `BUILD SUCCESS`. If Java reports a generic builder type mismatch for `UpsertDocRequest.builder().docs(docs).build()`, change it to:

```java
UpsertDocRequest request = (UpsertDocRequest) UpsertDocRequest.builder().docs(docs).build();
Response<?> response = collection.upsert(request);
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/agentdemo/rag/vector
git commit -m "Add DashVector vector gateway"
```

---

### Task 4: Document Indexing Service

**Files:**
- Create: `src/main/java/com/example/agentdemo/rag/DocumentIndexingService.java`
- Modify: `src/main/java/com/example/agentdemo/rag/RagService.java`
- Create: `src/test/java/com/example/agentdemo/rag/DocumentIndexingServiceTest.java`

- [ ] **Step 1: Write indexing test with fake dependencies**

Create `src/test/java/com/example/agentdemo/rag/DocumentIndexingServiceTest.java`:

```java
package com.example.agentdemo.rag;

import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorSearchResult;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentIndexingServiceTest {

    @Test
    void indexesChunksIntoGatewayAndSavesMetadata() {
        DocumentChunkRepository repository = mock(DocumentChunkRepository.class);
        EmbeddingModel embeddingModel = new FakeEmbeddingModel();
        FakeGateway gateway = new FakeGateway();
        RagProperties properties = new RagProperties();
        properties.getRag().setChunkSize(10);
        properties.getRag().setChunkOverlap(0);
        DocumentIndexingService service = new DocumentIndexingService(repository, embeddingModel, gateway, properties);

        DocumentEntity document = new DocumentEntity("Title", "abcdefghijklmnop");
        org.springframework.test.util.ReflectionTestUtils.setField(document, "id", 7L);

        service.index(document);

        assertThat(gateway.documents).hasSize(2);
        assertThat(gateway.documents.get(0).id()).isEqualTo("doc-7-chunk-0");
        assertThat(gateway.documents.get(0).metadata()).containsEntry("documentId", 7L);
        verify(repository).saveAll(org.mockito.ArgumentMatchers.argThat(chunks -> chunks.iterator().hasNext()));
    }

    static final class FakeGateway implements VectorStoreGateway {
        final List<VectorDocument> documents = new ArrayList<>();
        public String name() { return "fake"; }
        public boolean isConfigured() { return true; }
        public void ensureCollection() { }
        public void upsert(List<VectorDocument> documents) { this.documents.addAll(documents); }
        public List<VectorSearchResult> search(float[] queryVector, int topK) { return List.of(); }
    }

    static final class FakeEmbeddingModel implements EmbeddingModel {
        public EmbeddingResponse call(EmbeddingRequest request) { throw new UnsupportedOperationException(); }
        public float[] embed(Document document) { return new float[] {1.0f, 0.0f}; }
        public List<float[]> embed(List<String> texts) {
            return texts.stream().map(text -> new float[] {1.0f, text.length()}).toList();
        }
    }
}
```

- [ ] **Step 2: Run failing test**

Run:

```bash
./mvnw -Dtest=DocumentIndexingServiceTest test
```

Expected: FAIL because `DocumentIndexingService` does not exist.

- [ ] **Step 3: Add indexing service**

Create `src/main/java/com/example/agentdemo/rag/DocumentIndexingService.java`:

```java
package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class DocumentIndexingService {

    private final DocumentChunkRepository chunkRepository;
    private final Supplier<EmbeddingModel> embeddingModelSupplier;
    private final VectorStoreGateway vectorStoreGateway;
    private final RagProperties ragProperties;

    public DocumentIndexingService(DocumentChunkRepository chunkRepository,
            ObjectProvider<EmbeddingModel> embeddingModelProvider, VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties) {
        this(chunkRepository, embeddingModelProvider::getIfAvailable, vectorStoreGateway, ragProperties);
    }

    DocumentIndexingService(DocumentChunkRepository chunkRepository, EmbeddingModel embeddingModel,
            VectorStoreGateway vectorStoreGateway, RagProperties ragProperties) {
        this(chunkRepository, () -> embeddingModel, vectorStoreGateway, ragProperties);
    }

    private DocumentIndexingService(DocumentChunkRepository chunkRepository,
            Supplier<EmbeddingModel> embeddingModelSupplier, VectorStoreGateway vectorStoreGateway,
            RagProperties ragProperties) {
        this.chunkRepository = chunkRepository;
        this.embeddingModelSupplier = embeddingModelSupplier;
        this.vectorStoreGateway = vectorStoreGateway;
        this.ragProperties = ragProperties;
    }

    @Transactional
    public List<DocumentChunkEntity> index(DocumentEntity document) {
        if (!vectorStoreGateway.isConfigured()) {
            return List.of();
        }
        EmbeddingModel embeddingModel = embeddingModelSupplier.get();
        if (embeddingModel == null) {
            throw new BusinessException("EMBEDDING_MODEL_NOT_CONFIGURED", "DashScope EmbeddingModel is not configured");
        }
        TextChunker chunker = new TextChunker(ragProperties.getRag().getChunkSize(),
                ragProperties.getRag().getChunkOverlap());
        List<String> chunks = chunker.split(document.getContent());
        if (chunks.isEmpty()) {
            return List.of();
        }
        vectorStoreGateway.ensureCollection();
        List<float[]> embeddings = embeddingModel.embed(chunks);
        List<DocumentChunkEntity> chunkEntities = new ArrayList<>();
        List<VectorDocument> vectorDocuments = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String vectorId = "doc-" + document.getId() + "-chunk-" + i;
            DocumentChunkEntity chunk = new DocumentChunkEntity(document.getId(), i, vectorId, chunks.get(i));
            chunkEntities.add(chunk);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentId", document.getId());
            metadata.put("chunkIndex", i);
            metadata.put("title", document.getTitle() == null ? "" : document.getTitle());
            vectorDocuments.add(new VectorDocument(vectorId, embeddings.get(i), metadata));
        }
        vectorStoreGateway.upsert(vectorDocuments);
        return chunkRepository.saveAll(chunkEntities);
    }
}
```

- [ ] **Step 4: Modify RagService to call indexer**

Change `src/main/java/com/example/agentdemo/rag/RagService.java` constructor field list to include:

```java
private final DocumentIndexingService documentIndexingService;
```

Update constructor parameters:

```java
public RagService(DocumentRepository documentRepository, DocumentRetriever documentRetriever,
        DocumentIndexingService documentIndexingService, AiModelService aiModelService, TraceService traceService) {
    this.documentRepository = documentRepository;
    this.documentRetriever = documentRetriever;
    this.documentIndexingService = documentIndexingService;
    this.aiModelService = aiModelService;
    this.traceService = traceService;
}
```

Update `saveDocument`:

```java
@Transactional
public DocumentResponse saveDocument(DocumentRequest request) {
    DocumentEntity document = documentRepository.save(new DocumentEntity(request.title(), request.content()));
    documentIndexingService.index(document);
    return toDocumentResponse(document);
}
```

- [ ] **Step 5: Run tests and package**

Run:

```bash
./mvnw -Dtest=TextChunkerTest,DocumentIndexingServiceTest test
./mvnw clean package
```

Expected: both commands end with `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/agentdemo/rag/DocumentIndexingService.java \
  src/main/java/com/example/agentdemo/rag/RagService.java \
  src/test/java/com/example/agentdemo/rag/DocumentIndexingServiceTest.java
git commit -m "Index RAG documents into DashVector"
```

---

### Task 5: Vector Retriever And Fallback Wiring

**Files:**
- Modify: `src/main/java/com/example/agentdemo/rag/DocumentRetriever.java`
- Modify: `src/main/java/com/example/agentdemo/rag/KeywordDocumentRetriever.java`
- Create: `src/main/java/com/example/agentdemo/rag/DashVectorDocumentRetriever.java`
- Create: `src/main/java/com/example/agentdemo/rag/DocumentRetrieverConfig.java`
- Modify: `src/main/java/com/example/agentdemo/rag/RagService.java`
- Create: `src/test/java/com/example/agentdemo/rag/DashVectorDocumentRetrieverTest.java`

- [ ] **Step 1: Add retriever name method**

Modify `DocumentRetriever`:

```java
package com.example.agentdemo.rag;

import com.example.agentdemo.rag.dto.RetrievedContext;

import java.util.List;

public interface DocumentRetriever {

    String name();

    List<RetrievedContext> retrieve(String query, int limit);
}
```

Modify `KeywordDocumentRetriever`:

```java
@Override
public String name() {
    return "KeywordDocumentRetriever";
}
```

- [ ] **Step 2: Add vector retriever**

Create `src/main/java/com/example/agentdemo/rag/DashVectorDocumentRetriever.java`:

```java
package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.dto.RetrievedContext;
import com.example.agentdemo.rag.vector.VectorSearchResult;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class DashVectorDocumentRetriever implements DocumentRetriever {

    private final VectorStoreGateway vectorStoreGateway;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;

    public DashVectorDocumentRetriever(VectorStoreGateway vectorStoreGateway, DocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository, ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.vectorStoreGateway = vectorStoreGateway;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    @Override
    public String name() {
        return "DashVectorDocumentRetriever";
    }

    @Override
    public List<RetrievedContext> retrieve(String query, int limit) {
        if (!vectorStoreGateway.isConfigured()) {
            throw new BusinessException("VECTOR_STORE_NOT_CONFIGURED", "DashVector is not configured");
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new BusinessException("EMBEDDING_MODEL_NOT_CONFIGURED", "DashScope EmbeddingModel is not configured");
        }
        List<VectorSearchResult> results = vectorStoreGateway.search(embeddingModel.embed(query), limit);
        List<String> vectorIds = results.stream().map(VectorSearchResult::id).toList();
        Map<String, DocumentChunkEntity> chunksByVectorId = chunkRepository.findByVectorIdIn(vectorIds)
                .stream()
                .collect(Collectors.toMap(DocumentChunkEntity::getVectorId, Function.identity()));
        List<Long> documentIds = chunksByVectorId.values()
                .stream()
                .map(DocumentChunkEntity::getDocumentId)
                .distinct()
                .toList();
        Map<Long, DocumentEntity> documentsById = StreamSupport
                .stream(documentRepository.findAllById(documentIds).spliterator(), false)
                .collect(Collectors.toMap(DocumentEntity::getId, Function.identity()));
        return results.stream()
                .map(result -> toContext(result, chunksByVectorId, documentsById))
                .filter(context -> context != null)
                .sorted(Comparator.comparingDouble(RetrievedContext::score).reversed())
                .toList();
    }

    private RetrievedContext toContext(VectorSearchResult result, Map<String, DocumentChunkEntity> chunksByVectorId,
            Map<Long, DocumentEntity> documentsById) {
        DocumentChunkEntity chunk = chunksByVectorId.get(result.id());
        if (chunk == null) {
            return null;
        }
        DocumentEntity document = documentsById.get(chunk.getDocumentId());
        String title = document == null ? "" : document.getTitle();
        return new RetrievedContext(chunk.getDocumentId(), title, chunk.getContent(), result.score());
    }
}
```

- [ ] **Step 3: Add retriever configuration**

Create `src/main/java/com/example/agentdemo/rag/DocumentRetrieverConfig.java`:

```java
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
            DocumentRepository documentRepository, DocumentChunkRepository chunkRepository,
            ObjectProvider<EmbeddingModel> embeddingModelProvider, KeywordDocumentRetriever keywordDocumentRetriever) {
        boolean dashVectorRequested = "dashvector".equalsIgnoreCase(ragProperties.getRag().getRetriever());
        if (dashVectorRequested && vectorStoreGateway.isConfigured()) {
            return new DashVectorDocumentRetriever(vectorStoreGateway, documentRepository, chunkRepository,
                    embeddingModelProvider);
        }
        return keywordDocumentRetriever;
    }
}
```

- [ ] **Step 4: Update RagService trace node name**

Replace hard-coded keyword retrieval in `RagService.chat`:

```java
activeStep = traceService.startStep(run.getRunId(), "rag_retrieve",
        Map.of("query", request.message(), "retriever", documentRetriever.name()));
List<RetrievedContext> contexts = documentRetriever.retrieve(request.message(), 5);
traceService.completeStep(activeStep.getStepId(), contexts);
activeStep = null;
```

If retrieval throws and keyword fallback is enabled, this task does not yet retry; retry is Task 6.

- [ ] **Step 5: Run package**

Run:

```bash
./mvnw clean package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/agentdemo/rag/DocumentRetriever.java \
  src/main/java/com/example/agentdemo/rag/KeywordDocumentRetriever.java \
  src/main/java/com/example/agentdemo/rag/DashVectorDocumentRetriever.java \
  src/main/java/com/example/agentdemo/rag/DocumentRetrieverConfig.java \
  src/main/java/com/example/agentdemo/rag/RagService.java
git commit -m "Use DashVector for RAG retrieval"
```

---

### Task 6: Fallback, Trace, README, And Manual Verification

**Files:**
- Modify: `src/main/java/com/example/agentdemo/rag/RagService.java`
- Modify: `README.md`
- Modify: `.env.example`

- [ ] **Step 1: Add keyword fallback on vector retrieval failure**

Modify `RagService` constructor to inject `KeywordDocumentRetriever` and `RagProperties`:

```java
private final KeywordDocumentRetriever keywordDocumentRetriever;
private final RagProperties ragProperties;
```

Constructor parameters:

```java
KeywordDocumentRetriever keywordDocumentRetriever, RagProperties ragProperties
```

Add a helper that owns primary retrieval trace and fallback trace:

```java
private List<RetrievedContext> retrieveWithFallback(String runId, String message) {
    RunStepEntity retrieveStep = traceService.startStep(runId, "rag_retrieve",
            Map.of("query", message, "retriever", documentRetriever.name()));
    try {
        List<RetrievedContext> contexts = documentRetriever.retrieve(message, ragProperties.getRag().getTopK());
        traceService.completeStep(retrieveStep.getStepId(), contexts);
        return contexts;
    }
    catch (RuntimeException ex) {
        traceService.failStep(retrieveStep.getStepId(), ex);
        if (!ragProperties.getRag().isKeywordFallbackEnabled()
                || keywordDocumentRetriever.name().equals(documentRetriever.name())) {
            throw ex;
        }
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        RunStepEntity fallbackStep = traceService.startStep(runId, "rag_keyword_fallback_retrieve",
                Map.of("query", message, "reason", reason, "retriever", keywordDocumentRetriever.name()));
        try {
            List<RetrievedContext> contexts = keywordDocumentRetriever.retrieve(message, ragProperties.getRag().getTopK());
            traceService.completeStep(fallbackStep.getStepId(), contexts);
            return contexts;
        }
        catch (RuntimeException fallbackEx) {
            traceService.failStep(fallbackStep.getStepId(), fallbackEx);
            throw fallbackEx;
        }
    }
}
```

Use it in `chat`:

```java
activeStep = null;
List<RetrievedContext> contexts = retrieveWithFallback(run.getRunId(), request.message());
```

Do not wrap this helper in another retrieval step. The outer `activeStep` remains for `rag_generate_answer` and later steps.

- [ ] **Step 2: Update README**

Add a section under environment variables:

```markdown
### DashVector RAG

For real vector retrieval, configure DashScope embeddings and DashVector:

```bash
export AI_DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4
export AI_DASHSCOPE_EMBEDDING_DIMENSION=1024
export DASHVECTOR_ENDPOINT=vrs-cn-ln34u9ivx0001j.dashvector.cn-shanghai.aliyuncs.com
export DASHVECTOR_API_KEY=your-dashvector-api-key
export DASHVECTOR_COLLECTION=agent_rag_docs
export DASHVECTOR_DIMENSION=1024
export DASHVECTOR_METRIC=cosine
```

Do not commit real DashVector or DashScope keys. The application keeps H2 for source documents and chunk metadata, and stores vectors in DashVector.
```

- [ ] **Step 3: Build**

Run:

```bash
./mvnw clean package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Configure local `.env` without printing secrets**

Run commands that do not echo the key:

```bash
printf '\nDASHVECTOR_ENDPOINT=vrs-cn-ln34u9ivx0001j.dashvector.cn-shanghai.aliyuncs.com\n' >> .env
printf 'DASHVECTOR_COLLECTION=agent_rag_docs\nDASHVECTOR_DIMENSION=1024\nDASHVECTOR_METRIC=cosine\n' >> .env
printf 'AI_DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4\nAI_DASHSCOPE_EMBEDDING_DIMENSION=1024\n' >> .env
chmod 600 .env
```

Set `DASHVECTOR_API_KEY` by editing `.env` locally. Do not print it in terminal output.

- [ ] **Step 5: Restart app**

If port 8080 is occupied by the previous Spring Boot run, stop that process first:

```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN
kill <PID>
```

Start:

```bash
set -a
. ./.env
set +a
./mvnw spring-boot:run
```

Expected: Tomcat starts on port 8080.

- [ ] **Step 6: Verify document indexing and vector RAG**

Run:

```bash
curl -sS -X POST http://localhost:8080/api/rag/documents \
  -H 'Content-Type: application/json' \
  -d '{"title":"DashVector Demo","content":"DashVector stores document chunk embeddings for semantic retrieval in Alibaba Cloud."}'

curl -sS -X POST http://localhost:8080/api/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"rag-vector-1","message":"What stores embeddings for semantic retrieval?"}'
```

Expected:

- The document call succeeds.
- The chat call returns an answer and `retrievedContext` contains the DashVector Demo chunk.
- The returned `runId` can be queried.

- [ ] **Step 7: Verify trace**

Run:

```bash
curl -sS http://localhost:8080/api/runs/{runId}/steps
```

Expected: steps include `rag_retrieve` and `rag_generate_answer`; if DashVector failed and fallback was used, steps also include `rag_keyword_fallback_retrieve`.

- [ ] **Step 8: Commit docs and fallback changes**

```bash
git add README.md .env.example src/main/java/com/example/agentdemo/rag/RagService.java
git commit -m "Document DashVector RAG setup"
```

---

## Final Verification

Run:

```bash
./mvnw clean package
rg -n 'sk-[A-Za-z0-9]|DASHVECTOR_API_KEY=sk-|AI_DASHSCOPE_API_KEY=sk-' . -g '!target/**' -g '!.env'
git status --short
```

Expected:

- Maven build succeeds.
- Secret scan returns no real keys.
- Only intentional files are modified or committed.

Manual endpoint checks:

```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/api/runs
```

Expected:

- Health is `UP`.
- Runs show RAG executions after manual verification.
