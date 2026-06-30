package com.example.agentdemo.rag;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.rag.dto.DocumentDetailResponse;
import com.example.agentdemo.rag.dto.DocumentPageResponse;
import com.example.agentdemo.rag.dto.DocumentSummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RagControllerWebTest {

    @Test
    void listDocumentsRouteReturnsPagedResponse() throws Exception {
        DocumentManagementService managementService = mock(DocumentManagementService.class);
        when(managementService.listDocuments(0, 20)).thenReturn(new DocumentPageResponse(
                List.of(new DocumentSummaryResponse(1L, "Doc", 12, DocumentIndexStatus.READY,
                        Instant.parse("2026-06-25T00:00:00Z"))),
                0, 20, 1, 1));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RagController(mock(RagService.class), managementService))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/rag/documents?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(1))
                .andExpect(jsonPath("$.data.content[0].indexStatus").value("READY"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getDocumentRouteReturnsDetail() throws Exception {
        DocumentManagementService managementService = mock(DocumentManagementService.class);
        when(managementService.getDocument(1L)).thenReturn(
                new DocumentDetailResponse(1L, "Doc", "content", DocumentIndexStatus.READY, Instant.now()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RagController(mock(RagService.class), managementService))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/rag/documents/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Doc"))
                .andExpect(jsonPath("$.data.indexStatus").value("READY"));
    }

    @Test
    void deleteDocumentRouteDelegatesToService() throws Exception {
        DocumentManagementService managementService = mock(DocumentManagementService.class);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RagController(mock(RagService.class), managementService))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();

        mockMvc.perform(delete("/api/rag/documents/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(managementService).deleteDocument(1L);
    }

    @Test
    void updateDocumentRouteDelegatesToService() throws Exception {
        DocumentManagementService managementService = mock(DocumentManagementService.class);
        when(managementService.updateDocument(org.mockito.Mockito.eq(1L), org.mockito.Mockito.any()))
                .thenReturn(new com.example.agentdemo.rag.dto.DocumentResponse(
                        1L, "Updated", 15, DocumentIndexStatus.PENDING, Instant.now()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RagController(mock(RagService.class), managementService))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();

        mockMvc.perform(put("/api/rag/documents/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Updated\",\"content\":\"updated content\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Updated"))
                .andExpect(jsonPath("$.data.indexStatus").value("PENDING"));

        verify(managementService).updateDocument(org.mockito.Mockito.eq(1L), org.mockito.Mockito.any());
    }

    @Test
    void listDocumentsRouteRejectsInvalidPage() throws Exception {
        DocumentManagementService managementService = mock(DocumentManagementService.class);
        when(managementService.listDocuments(-1, 20)).thenThrow(
                new BusinessException("DOCUMENT_QUERY_INVALID", "page must be greater than or equal to 0"));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new RagController(mock(RagService.class), managementService))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/api/rag/documents?page=-1&size=20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DOCUMENT_QUERY_INVALID"));
    }

}
