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
