package com.example.agentdemo.order;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.order.dto.OrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class OrderManagementServiceTest {

    private final DemoOrderRepository repository = mock(DemoOrderRepository.class);
    private final OrderManagementService service = new OrderManagementService(repository);

    @Test
    void createsOrderForToolLookup() {
        OrderRequest request = orderRequest("20260630009", "NEW");
        when(repository.existsById("20260630009")).thenReturn(false);
        when(repository.save(org.mockito.Mockito.any(DemoOrderEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.createOrder(request);

        assertThat(response.orderId()).isEqualTo("20260630009");
        assertThat(response.status()).isEqualTo("NEW");
        assertThat(response.source()).isEqualTo("database:demo_orders");
        verify(repository).save(org.mockito.Mockito.any(DemoOrderEntity.class));
    }

    @Test
    void updatesOrderFactsUsedByToolLookup() {
        DemoOrderEntity existing = order("20260630001", "SHIPPED");
        when(repository.findById("20260630001")).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        var response = service.updateOrder("20260630001", orderRequest("20260630001", "RETURN_REQUESTED"));

        assertThat(response.status()).isEqualTo("RETURN_REQUESTED");
        assertThat(response.nextAction()).contains("客服");
        verify(repository).save(existing);
    }

    @Test
    void rejectsOrderIdMismatchWhenUpdating() {
        assertThatThrownBy(() -> service.updateOrder("20260630001", orderRequest("20260630002", "SHIPPED")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("orderId");
    }

    @Test
    void listsOrdersWithPagination() {
        PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "updatedAt"));
        when(repository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(order("20260630001", "SHIPPED")),
                pageable, 1));

        var page = service.listOrders(0, 20);

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().getFirst().orderId()).isEqualTo("20260630001");
        assertThat(page.totalElements()).isEqualTo(1);
    }

    private OrderRequest orderRequest(String orderId, String status) {
        return new OrderRequest(
                orderId,
                "Alice Chen",
                status,
                true,
                new BigDecimal("299.00"),
                "CNY",
                "SF Express",
                "SF20260630001",
                LocalDate.of(2026, 7, 2),
                "包裹已离开上海中转中心",
                "请客服基于当前订单状态给出下一步建议。");
    }

    private DemoOrderEntity order(String orderId, String status) {
        return new DemoOrderEntity(orderId, "Alice Chen", status, true, new BigDecimal("299.00"), "CNY",
                "SF Express", "SF20260630001", LocalDate.of(2026, 7, 2),
                "包裹已离开上海中转中心", "请客服基于当前订单状态给出下一步建议。");
    }

}
