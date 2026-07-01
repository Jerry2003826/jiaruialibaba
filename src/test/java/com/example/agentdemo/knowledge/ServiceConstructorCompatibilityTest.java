package com.example.agentdemo.knowledge;

import com.example.agentdemo.app.AppService;
import com.example.agentdemo.app.AppRepository;
import com.example.agentdemo.app.AppRevisionRepository;
import com.example.agentdemo.audit.AuditActorResolver;
import com.example.agentdemo.audit.AuditLogRepository;
import com.example.agentdemo.audit.AuditService;
import com.example.agentdemo.config.RagProperties;
import com.example.agentdemo.rag.DocumentIndexingService;
import com.example.agentdemo.rag.DocumentManagementService;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.trace.RunRepository;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ServiceConstructorCompatibilityTest {

    @Test
    void legacyAppServiceConstructorRemainsAvailable() {
        AppService service = new AppService(mock(AppRepository.class), mock(AppRevisionRepository.class),
                mock(WorkflowDefinitionService.class), mock(RunRepository.class),
                new AuditService(mock(AuditLogRepository.class), mock(AuditActorResolver.class)), new ObjectMapper());

        assertThat(service).isNotNull();
    }

    @Test
    void legacyKnowledgeServiceConstructorsRemainAvailable() {
        KnowledgeResponseMapper mapper = new KnowledgeResponseMapper(new ObjectMapper());
        KnowledgeDocumentService documentService = new KnowledgeDocumentService(mock(KnowledgeBaseAccessService.class),
                mock(DocumentRepository.class), mock(DocumentIndexingService.class),
                mock(DocumentManagementService.class), mapper);
        KnowledgeChunkPreviewService previewService = new KnowledgeChunkPreviewService(
                mock(KnowledgeBaseAccessService.class), new RagProperties(), mapper);

        assertThat(mapper).isNotNull();
        assertThat(documentService).isNotNull();
        assertThat(previewService).isNotNull();
    }

}
