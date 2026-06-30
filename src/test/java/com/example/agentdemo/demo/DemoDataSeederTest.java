package com.example.agentdemo.demo;

import com.example.agentdemo.order.DemoOrderRepository;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.rag.dto.DocumentRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DemoDataSeederTest {

    @Test
    void seedsShippedOrderReturnPolicyIntoKnowledgeBase() {
        DemoOrderRepository orderRepository = mock(DemoOrderRepository.class);
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        RagService ragService = mock(RagService.class);
        DemoDataSeeder seeder = new DemoDataSeeder(orderRepository, documentRepository, ragService);

        seeder.seedKnowledgeDocuments();

        ArgumentCaptor<DocumentRequest> requestCaptor = ArgumentCaptor.forClass(DocumentRequest.class);
        verify(ragService, atLeastOnce()).saveDocument(requestCaptor.capture());
        List<DocumentRequest> requests = requestCaptor.getAllValues();
        assertThat(requests).anySatisfy(request -> {
            assertThat(request.title()).isEqualTo("已发货订单退货流程");
            assertThat(request.content()).contains("拒收", "签收后提交退货退款申请", "已发货订单退货流程");
        });
    }
}
