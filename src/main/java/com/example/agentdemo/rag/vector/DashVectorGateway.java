package com.example.agentdemo.rag.vector;

import com.aliyun.dashvector.DashVectorClient;
import com.aliyun.dashvector.DashVectorCollection;
import com.aliyun.dashvector.common.DashVectorException;
import com.aliyun.dashvector.common.ErrorCode;
import com.aliyun.dashvector.models.CollectionMeta;
import com.aliyun.dashvector.models.Doc;
import com.aliyun.dashvector.models.Vector;
import com.aliyun.dashvector.models.requests.CreateCollectionRequest;
import com.aliyun.dashvector.models.requests.DeleteDocRequest;
import com.aliyun.dashvector.models.requests.QueryDocRequest;
import com.aliyun.dashvector.models.requests.UpsertDocRequest;
import com.aliyun.dashvector.models.responses.Response;
import com.aliyun.dashvector.proto.CollectionInfo;
import com.aliyun.dashvector.proto.FieldType;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.config.RagProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DashVectorGateway implements VectorStoreGateway {

    private final RagProperties.Dashvector properties;

    private final AtomicBoolean collectionChecked = new AtomicBoolean(false);

    private final Object collectionMonitor = new Object();

    private final Object clientMonitor = new Object();

    private volatile DashVectorClient sharedClient;

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
        synchronized (collectionMonitor) {
            if (collectionChecked.get()) {
                return;
            }
            ensureCollectionInternal();
            collectionChecked.set(true);
        }
    }

    private void ensureCollectionInternal() {
        try {
            DashVectorClient client = client();
            Response<CollectionMeta> describe = client.describe(properties.getCollection());
            if (!describe.isSuccess()) {
                if (describe.getCode() != ErrorCode.INEXISTENT_COLLECTION.getCode()) {
                    throw new BusinessException("VECTOR_STORE_INDEX_FAILED",
                            "Failed to describe DashVector collection: " + describe.getMessage());
                }
                createCollection(client);
            }
            else {
                validateCollection(describe.getOutput());
            }
        }
        catch (DashVectorException ex) {
            throw new BusinessException("VECTOR_STORE_INDEX_FAILED", "Failed to ensure DashVector collection", ex);
        }
    }

    private void createCollection(DashVectorClient client) {
            Response<Void> create = client.create(CreateCollectionRequest.builder()
                    .name(properties.getCollection())
                    .dimension(properties.getDimension())
                    .metric(metric())
                    .dataType(CollectionInfo.DataType.FLOAT)
                    .filedSchema("ownerId", FieldType.STRING)
                    .filedSchema("kbId", FieldType.STRING)
                    .build());
        if (!create.isSuccess() && create.getCode() == ErrorCode.DUPLICATE_COLLECTION.getCode()) {
            return;
        }
        ensureSuccess(create, "create DashVector collection", "VECTOR_STORE_INDEX_FAILED");
    }

    private void validateCollection(CollectionMeta collectionMeta) {
        if (collectionMeta == null) {
            throw new BusinessException("VECTOR_STORE_INDEX_FAILED",
                    "DashVector collection metadata is empty: " + properties.getCollection());
        }
        if (collectionMeta.getDimension() != properties.getDimension()) {
            throw new BusinessException("VECTOR_STORE_INDEX_FAILED",
                    "DashVector collection dimension " + collectionMeta.getDimension()
                            + " does not match configured dimension " + properties.getDimension());
        }
        if (collectionMeta.getMetric() != metric()) {
            throw new BusinessException("VECTOR_STORE_INDEX_FAILED",
                    "DashVector collection metric " + collectionMeta.getMetric()
                            + " does not match configured metric " + properties.getMetric());
        }
        if (collectionMeta.getDataType() != CollectionInfo.DataType.FLOAT) {
            throw new BusinessException("VECTOR_STORE_INDEX_FAILED",
                    "DashVector collection data type " + collectionMeta.getDataType()
                            + " does not match required FLOAT data type");
        }
    }

    @Override
    public void upsert(List<VectorDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        if (!isConfigured()) {
            throw new BusinessException("VECTOR_STORE_NOT_CONFIGURED", "DashVector is not configured");
        }
        ensureCollection();
        List<Doc> docs = documents.stream()
                .map(document -> Doc.builder()
                        .id(document.id())
                        .vector(toVector(document.vector()))
                        .fields(document.metadata())
                        .build())
                .toList();
        try {
            DashVectorClient client = client();
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
    }

    @Override
    public void delete(Collection<String> vectorIds) {
        if (vectorIds == null || vectorIds.isEmpty()) {
            return;
        }
        if (!isConfigured()) {
            return;
        }
        ensureCollection();
        try {
            DashVectorClient client = client();
            DashVectorCollection collection = client.get(properties.getCollection());
            if (!collection.isSuccess()) {
                throw new BusinessException("VECTOR_STORE_INDEX_FAILED", collection.getMessage());
            }
            Response<?> response = collection.delete(DeleteDocRequest.builder()
                    .ids(new ArrayList<>(vectorIds))
                    .build());
            ensureSuccess(response, "delete DashVector documents", "VECTOR_STORE_INDEX_FAILED");
        }
        catch (DashVectorException ex) {
            throw new BusinessException("VECTOR_STORE_INDEX_FAILED", "Failed to delete DashVector documents", ex);
        }
    }

    @Override
    public List<VectorSearchResult> search(float[] queryVector, int topK, Map<String, Object> metadataFilter) {
        if (!isConfigured()) {
            throw new BusinessException("VECTOR_STORE_NOT_CONFIGURED", "DashVector is not configured");
        }
        ensureCollection();
        try {
            DashVectorClient client = client();
            DashVectorCollection collection = client.get(properties.getCollection());
            if (!collection.isSuccess()) {
                throw new BusinessException("VECTOR_STORE_SEARCH_FAILED", collection.getMessage());
            }
            QueryDocRequest.QueryDocRequestBuilder request = QueryDocRequest.builder()
                    .vector(toVector(queryVector))
                    .topk(topK)
                    .includeVector(false);
            String filter = metadataFilter(metadataFilter);
            if (StringUtils.hasText(filter)) {
                request.filter(filter);
            }
            Response<List<Doc>> response = collection.query(request.build());
            ensureSuccess(response, "search DashVector documents", "VECTOR_STORE_SEARCH_FAILED");
            List<Doc> output = response.getOutput();
            if (output == null) {
                return List.of();
            }
            return output.stream()
                    .map(doc -> new VectorSearchResult(doc.getId(), doc.getScore(), doc.getFields()))
                    .toList();
        }
        catch (DashVectorException ex) {
            throw new BusinessException("VECTOR_STORE_SEARCH_FAILED", "Failed to search DashVector documents", ex);
        }
    }

    private DashVectorClient client() throws DashVectorException {
        DashVectorClient client = sharedClient;
        if (client == null) {
            synchronized (clientMonitor) {
                client = sharedClient;
                if (client == null) {
                    client = new DashVectorClient(properties.getApiKey(), properties.getEndpoint());
                    sharedClient = client;
                }
            }
        }
        return client;
    }

    @PreDestroy
    public void close() {
        DashVectorClient client = sharedClient;
        if (client != null) {
            synchronized (clientMonitor) {
                client = sharedClient;
                if (client != null) {
                    client.close();
                    sharedClient = null;
                }
            }
        }
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

    private String metadataFilter(Map<String, Object> metadataFilter) {
        if (metadataFilter == null || metadataFilter.isEmpty()) {
            return null;
        }
        List<String> clauses = new ArrayList<>();
        appendEqualsFilter(clauses, "ownerId", metadataFilter.get("ownerId"));
        appendEqualsFilter(clauses, "kbId", metadataFilter.get("kbId"));
        return clauses.isEmpty() ? null : String.join(" and ", clauses);
    }

    private void appendEqualsFilter(List<String> clauses, String field, Object value) {
        if (value instanceof String text && StringUtils.hasText(text)) {
            clauses.add(field + " = '" + text.replace("'", "\\'") + "'");
        }
    }

    private void ensureSuccess(Response<?> response, String action, String errorCode) {
        if (!response.isSuccess()) {
            throw new BusinessException(errorCode, "Failed to " + action + ": " + response.getMessage());
        }
    }
}
