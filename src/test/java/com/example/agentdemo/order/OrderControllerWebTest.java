package com.example.agentdemo.order;

import com.example.agentdemo.order.dto.OrderPageResponse;
import com.example.agentdemo.order.dto.OrderResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerWebTest {

    @Test
    void listOrdersRouteReturnsPagedResponse() throws Exception {
        OrderManagementService service = mock(OrderManagementService.class);
        when(service.listOrders(0, 20)).thenReturn(new OrderPageResponse(List.of(response()), 0, 20, 1, 1));
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/orders?page=0&size=20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].orderId").value("20260630001"))
                .andExpect(jsonPath("$.data.content[0].source").value("database:demo_orders"));
    }

    @Test
    void getOrderRouteReturnsOrder() throws Exception {
        OrderManagementService service = mock(OrderManagementService.class);
        when(service.getOrder("20260630001")).thenReturn(response());
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(get("/api/orders/20260630001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHIPPED"));
    }

    @Test
    void createOrderRouteDelegatesToService() throws Exception {
        OrderManagementService service = mock(OrderManagementService.class);
        when(service.createOrder(org.mockito.Mockito.any())).thenReturn(response());
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("20260630001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("20260630001"));
    }

    @Test
    void updateOrderRouteDelegatesToService() throws Exception {
        OrderManagementService service = mock(OrderManagementService.class);
        when(service.updateOrder(org.mockito.Mockito.eq("20260630001"), org.mockito.Mockito.any()))
                .thenReturn(response());
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(put("/api/orders/20260630001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orderJson("20260630001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("20260630001"));
    }

    @Test
    void deleteOrderRouteDelegatesToService() throws Exception {
        OrderManagementService service = mock(OrderManagementService.class);
        MockMvc mockMvc = mockMvc(service);

        mockMvc.perform(delete("/api/orders/20260630001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).deleteOrder("20260630001");
    }

    private MockMvc mockMvc(OrderManagementService service) {
        return MockMvcBuilders.standaloneSetup(new OrderController(service))
                .setControllerAdvice(new com.example.agentdemo.common.GlobalExceptionHandler())
                .build();
    }

    private OrderResponse response() {
        return new OrderResponse(
                "20260630001",
                "Alice Chen",
                "SHIPPED",
                true,
                new BigDecimal("299.00"),
                "CNY",
                "SF Express",
                "SF20260630001",
                LocalDate.of(2026, 7, 2),
                "包裹已离开上海中转中心",
                "请客服基于当前订单状态给出下一步建议。",
                "database:demo_orders",
                Instant.parse("2026-06-30T00:00:00Z"),
                Instant.parse("2026-06-30T00:00:00Z"));
    }

    private String orderJson(String orderId) {
        return """
                {
                  "orderId": "%s",
                  "customerName": "Alice Chen",
                  "status": "SHIPPED",
                  "paid": true,
                  "amount": 299.00,
                  "currency": "CNY",
                  "carrier": "SF Express",
                  "trackingNumber": "SF20260630001",
                  "estimatedDelivery": "2026-07-02",
                  "latestEvent": "包裹已离开上海中转中心",
                  "nextAction": "请客服基于当前订单状态给出下一步建议。"
                }
                """.formatted(orderId);
    }

}
