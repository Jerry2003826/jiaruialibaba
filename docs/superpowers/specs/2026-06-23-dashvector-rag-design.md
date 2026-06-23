# DashVector RAG Design

## Goal

Replace the current keyword-only RAG retriever with a real Alibaba-backed vector retrieval path while keeping the demo small, runnable, and extensible.

The first implementation will use:

- DashScope `EmbeddingModel` for document and query embeddings.
- Alibaba Cloud DashVector as the external vector store.
- H2 for document metadata, source text, chunk metadata, and run trace.
- The existing `/api/rag/documents`, `/api/rag/chat`, workflow `retriever` node, and trace APIs.

This is not a full knowledge-base platform. It is the first production-shaped RAG slice that can later grow into datasets, indexing jobs, hybrid search, reranking, and Dify-like workflow retrieval nodes.

## Confirmed Runtime Values

The user has created a DashVector Serverless cluster in `cn-shanghai` and approved this direction.

Non-secret values:

- DashVector endpoint: `vrs-cn-ln34u9ivx0001j.dashvector.cn-shanghai.aliyuncs.com`
- Collection: `agent_rag_docs`
- Metric: `cosine`
- Embedding model: `text-embedding-v4`
- Embedding dimension: `1024`

Secret values:

- DashVector API key must be stored only in local environment variables or `.env`.
- Real keys must never be committed to Git.

## Configuration

Add environment-driven configuration under `application.yml`:

```yaml
demo:
  rag:
    retriever: dashvector
    keyword-fallback-enabled: true
    chunk-size: 800
    chunk-overlap: 120
    top-k: 5
  dashvector:
    endpoint: ${DASHVECTOR_ENDPOINT:}
    api-key: ${DASHVECTOR_API_KEY:}
    collection: ${DASHVECTOR_COLLECTION:agent_rag_docs}
    dimension: ${DASHVECTOR_DIMENSION:1024}
    metric: ${DASHVECTOR_METRIC:cosine}
  ai:
    embedding-model: ${AI_DASHSCOPE_EMBEDDING_MODEL:text-embedding-v4}
```

The local `.env` may contain:

```bash
DASHVECTOR_ENDPOINT=vrs-cn-ln34u9ivx0001j.dashvector.cn-shanghai.aliyuncs.com
DASHVECTOR_API_KEY=your-dashvector-api-key
DASHVECTOR_COLLECTION=agent_rag_docs
DASHVECTOR_DIMENSION=1024
DASHVECTOR_METRIC=cosine
AI_DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4
```

`.env.example` will include placeholders only.

## Architecture

Keep the current controller and service boundaries:

```text
RagController
  -> RagService
    -> DocumentRepository
    -> DocumentIndexingService
      -> TextChunker
      -> EmbeddingModel
      -> VectorStoreGateway
    -> DocumentRetriever
      -> DashVectorDocumentRetriever
        -> EmbeddingModel
        -> VectorStoreGateway
```

New or changed components:

- `RagProperties`: typed configuration for chunking, retriever selection, DashVector, and embedding model.
- `AiConfig`: create a DashScope `EmbeddingModel` bean when `AI_DASHSCOPE_API_KEY` or `DASHSCOPE_API_KEY` is configured.
- `DocumentChunkEntity`: H2 metadata for each chunk, including document ID, chunk index, content, vector ID, and timestamps.
- `DocumentChunkRepository`: database access for chunk metadata.
- `TextChunker`: deterministic text splitting. It should avoid request-scoped state and expose a simple `List<String> split(String content)` method.
- `DocumentIndexingService`: saves chunk metadata and upserts vectors to DashVector.
- `VectorStoreGateway`: small project-level interface, so DashVector can later be replaced with OpenSearch, AnalyticDB, Elasticsearch, Milvus, or Spring AI `VectorStore`.
- `DashVectorGateway`: Alibaba DashVector SDK adapter.
- `DashVectorDocumentRetriever`: embeds the query, searches DashVector, loads chunk metadata from H2, and returns `RetrievedContext`.
- `KeywordDocumentRetriever`: kept as explicit fallback, not the default once DashVector is configured.

## Data Model

Keep `rag_documents` unchanged for source document metadata.

Add `rag_document_chunks`:

```text
id: Long
documentId: Long
chunkIndex: int
vectorId: String
content: CLOB
createdAt: Instant
updatedAt: Instant
```

`vectorId` should be deterministic enough for idempotent reindexing:

```text
doc-{documentId}-chunk-{chunkIndex}
```

For this first version, updating an existing document is out of scope because the current API only creates documents. Future updates should delete or overwrite old chunk vectors before reindexing.

## Indexing Flow

`POST /api/rag/documents`:

1. Validate request DTO.
2. Save `DocumentEntity` to H2.
3. Split content into chunks.
4. Ensure DashVector collection exists if DashVector is enabled.
5. Generate embeddings for chunks through DashScope `EmbeddingModel`.
6. Upsert vectors to DashVector with metadata:
   - `documentId`
   - `chunkId`
   - `chunkIndex`
   - `title`
7. Save `DocumentChunkEntity` rows to H2.
8. Return the existing `DocumentResponse`, with optional future fields left out of this slice.

If DashVector is not configured:

- The API should still save the document.
- If keyword fallback is enabled, retrieval can still use keyword matching.
- The response should not claim vector indexing succeeded.

## Retrieval Flow

`POST /api/rag/chat` and workflow `retriever` node:

1. Create the same run and run steps as today.
2. Start retrieval step named `rag_vector_retrieve` when DashVector is active.
3. Embed the query using DashScope `EmbeddingModel`.
4. Search DashVector top K.
5. Resolve matched vector IDs to H2 chunk rows.
6. Return `RetrievedContext` list using chunk text and similarity scores.
7. Generate the final answer with the existing `AiModelService`.
8. Mark run and steps succeeded or failed.

Fallback behavior:

- If DashVector is not configured and keyword fallback is enabled, use `KeywordDocumentRetriever` and trace the step as `rag_keyword_retrieve`.
- If DashVector is configured but the remote call fails, mark the vector step failed. For this demo, then use keyword fallback only when `demo.rag.keyword-fallback-enabled=true`; otherwise propagate a `BusinessException`.
- The API response must continue to include `retrievedContext`.

## Error Handling

Use existing `BusinessException` and `GlobalExceptionHandler`.

Expected error codes:

- `VECTOR_STORE_NOT_CONFIGURED`: DashVector is selected but endpoint or API key is missing and fallback is disabled.
- `VECTOR_STORE_INDEX_FAILED`: document saved but vector indexing failed.
- `VECTOR_STORE_SEARCH_FAILED`: query embedding or DashVector search failed.
- `EMBEDDING_MODEL_NOT_CONFIGURED`: embedding model bean is unavailable because DashScope key is missing and no fallback is allowed.

All RAG failures must still mark the run as `FAILED` and fail the active `run_step`.

## Security

- No DashVector API key in source, README examples, tests, or committed files.
- `.env` remains ignored.
- README and `.env.example` show placeholder values only.
- Logs must not print API keys or full authorization headers.
- Endpoint and collection name are not secrets and may be documented.

## Testing And Verification

Required local checks:

```bash
./mvnw clean package
```

Manual API checks after local `.env` is configured:

```bash
curl http://localhost:8080/api/health

curl -X POST http://localhost:8080/api/rag/documents \
  -H 'Content-Type: application/json' \
  -d '{"title":"DashVector Demo","content":"DashVector stores document chunk embeddings for semantic retrieval."}'

curl -X POST http://localhost:8080/api/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"rag-vector-1","message":"What stores embeddings for retrieval?"}'
```

Expected result:

- `/api/rag/documents` persists the document and indexes chunks.
- `/api/rag/chat` returns an answer and a non-empty `retrievedContext`.
- `GET /api/runs/{runId}/steps` shows `rag_vector_retrieve` and `rag_generate_answer`.
- `fallback:false` for the model answer when DashScope chat is working.

## Current Limits

- No document update or delete endpoint.
- No async indexing job queue.
- No hybrid BM25 + vector ranking.
- No reranker.
- No multi-tenant dataset isolation.
- No UI for collection management.
- No migration to persistent vector database beyond DashVector in this slice.

## Future Extension Points

- Replace `DashVectorGateway` with another `VectorStoreGateway` provider.
- Add Spring AI `VectorStore` adapter once the exact DashVector or Alibaba OpenSearch starter is selected.
- Add reranking after vector retrieval.
- Add chunk-level trace and indexing status.
- Add workflow node config for `topK`, collection, score threshold, and retrieval mode.
- Add MCP tools for document ingestion and search.
