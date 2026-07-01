package com.example.agentdemo.order;

import com.example.agentdemo.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseOrderLookupServiceTest {

    private final DemoOrderRepository repository = mock(DemoOrderRepository.class);
    private final DatabaseOrderLookupService service = new DatabaseOrderLookupService(repository);

    @Test
    void readsOrderFactsFromRepository() {
        when(repository.findByOrderIdAndOwnerId("20260630001", "workbench-dev"))
                .thenReturn(Optional.of(order()));

        Map<String, Object> output = service.lookup("请查订单 20260630001");

        assertThat(output)
                .containsEntry("orderId", "20260630001")
                .containsEntry("customerName", "Alice Chen")
                .containsEntry("status", "SHIPPED")
                .containsEntry("carrier", "SF Express")
                .containsEntry("trackingNumber", "SF20260630001")
                .containsEntry("source", "database:demo_orders");
        verify(repository).findByOrderIdAndOwnerId("20260630001", "workbench-dev");
    }

    @Test
    void requiresOrderIdInQuery() {
        assertThatThrownBy(() -> service.lookup("查一下我的订单"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Order id is required");
    }

    @Test
    void returnsStructuredResultWhenOrderIsMissing() {
        when(repository.findByOrderIdAndOwnerId("20260630002", "workbench-dev"))
                .thenReturn(Optional.empty());

        Map<String, Object> output = service.lookup("订单 20260630002");

        assertThat(output)
                .containsEntry("found", false)
                .containsEntry("orderId", "20260630002")
                .containsEntry("source", "database:demo_orders")
                .containsEntry("userQuery", "订单 20260630002");
    }

    @Test
    void normalisesAmountTrailingZeros() {
        when(repository.findByOrderIdAndOwnerId("20260630001", "workbench-dev"))
                .thenReturn(Optional.of(order()));

        Map<String, Object> output = service.lookup("Order id: 20260630001");

        assertThat(output.get("amount")).isEqualTo("299");
    }

    @Test
    void toleratesNullAmount() {
        DemoOrderEntity order = new DemoOrderEntity("20260630003", "Mina Zhang", "PROCESSING", false, null,
                "CNY", null, null, null, "Order created", "Ask for payment");
        when(repository.findByOrderIdAndOwnerId("20260630003", "workbench-dev"))
                .thenReturn(Optional.of(order));

        Map<String, Object> output = service.lookup("Order id: 20260630003");

        assertThat(output).containsEntry("found", true);
        assertThat(output.get("amount")).isNull();
    }

    private DemoOrderEntity order() {
        return new DemoOrderEntity(
                "20260630001",
                "Alice Chen",
                "SHIPPED",
                true,
                new BigDecimal("299.00"),
                "CNY",
                "SF Express",
                "SF20260630001",
                LocalDate.of(2026, 7, 2),
                "Package left the Shanghai transit center",
                "Tell the customer the order has shipped and provide the tracking number.");
    }

}
