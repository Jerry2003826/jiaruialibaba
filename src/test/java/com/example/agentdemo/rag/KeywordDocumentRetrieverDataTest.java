package com.example.agentdemo.rag;

import com.example.agentdemo.rag.dto.RetrievedContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the keyword retriever: documents must be retrievable as soon as their
 * content is persisted (PENDING / READY / FAILED), regardless of vector indexing state, and only
 * documents being removed (DELETING / DELETED) are excluded. Before the fix the retriever filtered
 * on READY only, so in a keyword-only / fallback deployment every new document was invisible.
 */
@DataJpaTest
class KeywordDocumentRetrieverDataTest {

    @Autowired
    private DocumentRepository documentRepository;

    @Test
    void retrievesPendingReadyAndFailedButNotDeletingDocuments() {
        long pending = save("Alpha guide", "alpha notes about widgets", DocumentEntity::markPending);
        long ready = save("Beta guide", "beta notes about widgets", DocumentEntity::markReady);
        long failed = save("Gamma guide", "gamma notes about widgets", DocumentEntity::markFailed);
        saveBuilder("Builder guidance", "builder-only widgets guidance");
        save("Delta guide", "delta widgets being removed", DocumentEntity::markDeleting);
        save("Epsilon guide", "epsilon widgets removed", DocumentEntity::markDeleted);

        KeywordDocumentRetriever retriever = new KeywordDocumentRetriever(documentRepository);
        List<RetrievedContext> results = retriever.retrieve("widgets", 10);

        assertThat(results)
                .extracting(RetrievedContext::documentId)
                .containsExactlyInAnyOrder(pending, ready, failed);
    }

    @Test
    void everyPublicRepositoryViewExcludesBuilderDocuments() {
        long publicDocument = save("Public guide", "public content", DocumentEntity::markReady);
        long builderDocument = saveBuilder("Builder guide", "internal guidance");

        assertThat(documentRepository.findPublicByOwnerIdAndIndexStatusNotOrderByCreatedAtDesc(
                "workbench-dev", DocumentIndexStatus.DELETED, PageRequest.of(0, 20)).getContent())
                .extracting(DocumentEntity::getId)
                .containsExactly(publicDocument);
        assertThat(documentRepository.findPublicByOwnerIdAndIdInAndIndexStatus(
                "workbench-dev", List.of(publicDocument, builderDocument), DocumentIndexStatus.READY))
                .extracting(DocumentEntity::getId)
                .containsExactly(publicDocument);
    }

    private long saveBuilder(String title, String content) {
        DocumentEntity document = new DocumentEntity(title, content);
        document.assignKnowledge("kb-builder", "BUILDER", null, "text/plain", (long) content.length(), "hash");
        document.markReady();
        return documentRepository.saveAndFlush(document).getId();
    }

    private long save(String title, String content, Consumer<DocumentEntity> status) {
        DocumentEntity document = new DocumentEntity(title, content);
        status.accept(document);
        return documentRepository.saveAndFlush(document).getId();
    }

}
