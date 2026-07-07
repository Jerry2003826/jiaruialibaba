package com.example.agentdemo.knowledge;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.knowledge.dto.ChunkPreviewResponse;
import com.example.agentdemo.knowledge.dto.CreateKnowledgeBaseRequest;
import com.example.agentdemo.knowledge.dto.KnowledgeBaseResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeDocumentResponse;
import com.example.agentdemo.knowledge.dto.KnowledgeSearchResponse;
import com.example.agentdemo.knowledge.dto.TextDocumentRequest;
import com.example.agentdemo.rag.DocumentIndexStatus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * P1-1 knowledge base: KB CRUD, text + file (PDF/docx via Tika) ingestion, index status,
 * per-KB search isolation with citations, parse-failure handling, reindex and delete.
 */
@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.profiles.group.dev=dev",
        "demo.alibaba.strict-mode=false",
        "demo.ai.fallback-enabled=true",
        "demo.rag.keyword-fallback-enabled=true",
        "spring.ai.dashscope.api-key=",
        "spring.datasource.url=jdbc:h2:mem:agent_backend_kb_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword",
        "demo.knowledge.max-content-chars=32",
        "demo.knowledge.max-scanned-documents=2"
})
@ExtendWith(OutputCaptureExtension.class)
class KnowledgeBaseIntegrationTest {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeIngestionService knowledgeIngestionService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeChunkPreviewService knowledgeChunkPreviewService;

    @Autowired
    private KnowledgeSearchService knowledgeSearchService;

    @Test
    void createAndListKnowledgeBase() {
        KnowledgeBaseResponse kb = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Docs", "product docs", null));
        assertThat(kb.kbId()).startsWith("kb-");
        assertThat(knowledgeBaseService.listKnowledgeBases()).extracting(KnowledgeBaseResponse::kbId)
                .contains(kb.kbId());
    }

    @Test
    void listKnowledgeBasesReturnsGroupedDocumentCounts() {
        String kbA = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("A", null, null)).kbId();
        String kbB = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("B", null, null)).kbId();
        knowledgeIngestionService.addTextDocument(kbA, new TextDocumentRequest("A1", "alpha"));
        knowledgeIngestionService.addTextDocument(kbA, new TextDocumentRequest("A2", "beta"));
        knowledgeIngestionService.addTextDocument(kbB, new TextDocumentRequest("B1", "gamma"));

        assertThat(knowledgeBaseService.listKnowledgeBases())
                .extracting(KnowledgeBaseResponse::kbId, KnowledgeBaseResponse::documentCount)
                .contains(tuple(kbA, 2L), tuple(kbB, 1L));
    }

    @Test
    void textIngestionIsRetrievableWithCitations() {
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Policies", null, null)).kbId();
        KnowledgeDocumentResponse doc = knowledgeIngestionService.addTextDocument(kbId,
                new TextDocumentRequest("Returns", "returns refund in 30 days"));
        // Keyword-only deployment marks documents READY immediately (no vector store).
        assertThat(doc.indexStatus()).isEqualTo(DocumentIndexStatus.READY);
        assertThat(doc.sourceType()).isEqualTo("TEXT");

        KnowledgeSearchResponse result = knowledgeSearchService.search(kbId, "returns refund", null);
        assertThat(result.citations()).isNotEmpty();
        assertThat(result.citations().get(0).documentId()).isEqualTo(doc.documentId());
        assertThat(result.citations().get(0).title()).isEqualTo("Returns");
        assertThat(result.citations().get(0).score()).isGreaterThan(0);
    }

    @Test
    void textIngestionRejectsContentOverConfiguredLimit() {
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Policies", null, null)).kbId();

        assertThatThrownBy(() -> knowledgeIngestionService.addTextDocument(kbId,
                new TextDocumentRequest("Too Large", "x".repeat(33))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException error = (BusinessException) ex;
                    assertThat(error.getCode()).isEqualTo("DOCUMENT_CONTENT_TOO_LARGE");
                    assertThat(error.getMessage()).contains("32");
                });
    }

    @Test
    void textIngestionValidationFailureDoesNotLogAuditResourceResolutionWarn(CapturedOutput output) {
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Policies", null, null)).kbId();

        assertThatThrownBy(() -> knowledgeIngestionService.addTextDocument(kbId,
                new TextDocumentRequest("Too Large", "x".repeat(33))))
                .isInstanceOf(BusinessException.class);

        assertThat(output).doesNotContain("Failed to resolve audit resourceId expression '#result.documentId()'");
    }

    @Test
    void textIngestionAcceptsConfiguredBoundaryLength() {
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Policies", null, null)).kbId();

        KnowledgeDocumentResponse doc = knowledgeIngestionService.addTextDocument(kbId,
                new TextDocumentRequest("Boundary", "x".repeat(32)));

        assertThat(doc.indexStatus()).isEqualTo(DocumentIndexStatus.READY);
        assertThat(doc.contentLength()).isEqualTo(32);
    }

    @Test
    void searchIsIsolatedPerKnowledgeBase() {
        String kbA = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("A", null, null)).kbId();
        String kbB = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("B", null, null)).kbId();
        knowledgeIngestionService.addTextDocument(kbA, new TextDocumentRequest("A doc", "alpha returns policy"));
        knowledgeIngestionService.addTextDocument(kbB, new TextDocumentRequest("B doc", "beta shipping policy"));

        assertThat(knowledgeSearchService.search(kbA, "returns", null).citations()).hasSize(1);
        assertThat(knowledgeSearchService.search(kbB, "returns", null).citations()).isEmpty();
    }

    @Test
    void searchSupportsSingleHanCharacterQueries() {
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Chinese Policies", null, null)).kbId();
        KnowledgeDocumentResponse doc = knowledgeIngestionService.addTextDocument(kbId,
                new TextDocumentRequest("退货政策", "退货和退款流程说明"));

        KnowledgeSearchResponse result = knowledgeSearchService.search(kbId, "退", null);

        assertThat(result.citations()).extracting(Citation::documentId).contains(doc.documentId());
    }

    @Test
    void searchStopsAfterConfiguredDocumentScanBudget() {
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Budgeted Search", null, null)).kbId();
        knowledgeIngestionService.addTextDocument(kbId, new TextDocumentRequest("Doc 1", "alpha policy"));
        knowledgeIngestionService.addTextDocument(kbId, new TextDocumentRequest("Doc 2", "beta policy"));
        knowledgeIngestionService.addTextDocument(kbId, new TextDocumentRequest("Doc 3", "needle policy"));

        KnowledgeSearchResponse result = knowledgeSearchService.search(kbId, "needle", 5);

        assertThat(result.citations()).isEmpty();
    }

    @Test
    void previewChunksSupportsPaginationGuardrail() {
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Chunk Preview", null, new RetrievalConfig(4, 0, null))).kbId();
        KnowledgeDocumentResponse doc = knowledgeIngestionService.addTextDocument(kbId,
                new TextDocumentRequest("Chunked", "abcdefghij"));

        ChunkPreviewResponse preview = knowledgeChunkPreviewService.previewChunks(kbId, doc.documentId(), 1, 2);

        assertThat(preview.page()).isEqualTo(1);
        assertThat(preview.size()).isEqualTo(2);
        assertThat(preview.totalChunks()).isEqualTo(3);
        assertThat(preview.totalPages()).isEqualTo(2);
        assertThat(preview.chunks()).extracting(ChunkPreviewResponse.Chunk::chunkIndex).containsExactly(2);
        assertThat(preview.chunks()).extracting(ChunkPreviewResponse.Chunk::content).containsExactly("ij");
    }

    @Test
    void chunkPreviewUsesDefaultPageAndSize() {
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Chunk Preview Defaults", null, new RetrievalConfig(4, 0, null))).kbId();
        KnowledgeDocumentResponse doc = knowledgeIngestionService.addTextDocument(kbId,
                new TextDocumentRequest("Chunked", "abcdefghij"));

        ChunkPreviewResponse preview = knowledgeChunkPreviewService.previewChunks(kbId, doc.documentId(), null, null);

        assertThat(preview.page()).isEqualTo(0);
        assertThat(preview.size()).isEqualTo(20);
        assertThat(preview.totalChunks()).isEqualTo(3);
        assertThat(preview.totalPages()).isEqualTo(1);
        assertThat(preview.chunks()).extracting(ChunkPreviewResponse.Chunk::chunkIndex).containsExactly(0, 1, 2);
    }

    @Test
    void chunkPreviewReturnsEmptyPageInsteadOfOverflowingForHugePageNumber() {
        String kbId = knowledgeBaseService.createKnowledgeBase(
                new CreateKnowledgeBaseRequest("Chunk Preview Huge Page", null, new RetrievalConfig(4, 0, null))).kbId();
        KnowledgeDocumentResponse doc = knowledgeIngestionService.addTextDocument(kbId,
                new TextDocumentRequest("Chunked", "abcdefghij"));

        ChunkPreviewResponse preview = knowledgeChunkPreviewService.previewChunks(kbId, doc.documentId(),
                Integer.MAX_VALUE, 2);

        assertThat(preview.page()).isEqualTo(Integer.MAX_VALUE);
        assertThat(preview.size()).isEqualTo(2);
        assertThat(preview.totalChunks()).isEqualTo(3);
        assertThat(preview.totalPages()).isEqualTo(2);
        assertThat(preview.chunks()).isEmpty();
    }

    @Test
    void pdfIngestionExtractsText() throws Exception {
        String kbId = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("Files", null, null))
                .kbId();
        byte[] pdf = pdfBytes("Hello PDF knowledge base about returns policy");
        MockMultipartFile file = new MockMultipartFile("file", "policy.pdf", "application/pdf", pdf);

        KnowledgeDocumentResponse doc = knowledgeIngestionService.addFileDocument(kbId, file);

        assertThat(doc.indexStatus()).isEqualTo(DocumentIndexStatus.READY);
        assertThat(doc.sourceType()).isEqualTo("FILE");
        assertThat(doc.fileName()).isEqualTo("policy.pdf");
        assertThat(doc.contentLength()).isGreaterThan(0);
        assertThat(knowledgeSearchService.search(kbId, "returns policy", null).citations()).isNotEmpty();
    }

    @Test
    void docxIngestionExtractsText() throws Exception {
        String kbId = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("Files", null, null))
                .kbId();
        byte[] docx = docxBytes("Hello DOCX knowledge base about shipping timelines");
        MockMultipartFile file = new MockMultipartFile("file", "shipping.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx);

        KnowledgeDocumentResponse doc = knowledgeIngestionService.addFileDocument(kbId, file);

        assertThat(doc.indexStatus()).isEqualTo(DocumentIndexStatus.READY);
        assertThat(knowledgeSearchService.search(kbId, "shipping", null).citations()).isNotEmpty();
    }

    @Test
    void txtIngestionSanitizesDangerousFileName() {
        String kbId = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("Files", null, null))
                .kbId();
        MockMultipartFile file = new MockMultipartFile("file", "../../notes.txt", "text/plain",
                "returns and refunds".getBytes());

        KnowledgeDocumentResponse doc = knowledgeIngestionService.addFileDocument(kbId, file);

        assertThat(doc.indexStatus()).isEqualTo(DocumentIndexStatus.READY);
        assertThat(doc.fileName()).isEqualTo("notes.txt");
        assertThat(doc.title()).isEqualTo("notes.txt");
    }

    @Test
    void zipFileIsRejectedBeforeParsing() {
        String kbId = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("Files", null, null))
                .kbId();
        MockMultipartFile file = new MockMultipartFile("file", "archive.zip", "application/zip",
                new byte[] { 'P', 'K', 3, 4, 20, 0 });

        assertThatThrownBy(() -> knowledgeIngestionService.addFileDocument(kbId, file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("DOCUMENT_MIME_NOT_ALLOWED"));
    }

    @Test
    void octetStreamFileIsRejectedBeforeParsing() {
        String kbId = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("Files", null, null))
                .kbId();
        MockMultipartFile file = new MockMultipartFile("file", "blob.bin", "application/octet-stream",
                new byte[] { 1, 2, 3, 4 });

        assertThatThrownBy(() -> knowledgeIngestionService.addFileDocument(kbId, file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("DOCUMENT_MIME_NOT_ALLOWED"));
    }

    @Test
    void unparseableFileIsRecordedAsFailed() {
        String kbId = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("Files", null, null))
                .kbId();
        // Whitespace-only text yields no extractable content.
        MockMultipartFile file = new MockMultipartFile("file", "blank.txt", "text/plain",
                "    \n\t  ".getBytes());

        KnowledgeDocumentResponse doc = knowledgeIngestionService.addFileDocument(kbId, file);

        assertThat(doc.indexStatus()).isEqualTo(DocumentIndexStatus.FAILED);
        assertThat(doc.errorMessage()).contains("No extractable text");
    }

    @Test
    void emptyFileIsRejected() {
        String kbId = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("Files", null, null))
                .kbId();
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> knowledgeIngestionService.addFileDocument(kbId, file))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("DOCUMENT_FILE_EMPTY"));
    }

    @Test
    void reindexAndDelete() {
        String kbId = knowledgeBaseService.createKnowledgeBase(new CreateKnowledgeBaseRequest("Ops", null, null))
                .kbId();
        KnowledgeDocumentResponse doc = knowledgeIngestionService.addTextDocument(kbId,
                new TextDocumentRequest("Doc", "some content to index"));

        assertThat(knowledgeDocumentService.reindex(kbId, doc.documentId()).indexStatus())
                .isEqualTo(DocumentIndexStatus.READY);

        knowledgeDocumentService.deleteDocument(kbId, doc.documentId());
        assertThatThrownBy(() -> knowledgeDocumentService.getDocument(kbId, doc.documentId()))
                .isInstanceOf(BusinessException.class);
    }

    private byte[] pdfBytes(String text) throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(text);
                stream.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] docxBytes(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(out);
            return out.toByteArray();
        }
    }

}
