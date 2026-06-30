package com.example.agentdemo.order;

import com.example.agentdemo.common.ApiResponse;
import com.example.agentdemo.order.dto.OrderPageResponse;
import com.example.agentdemo.order.dto.OrderRequest;
import com.example.agentdemo.order.dto.OrderResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderManagementService orderManagementService;

    public OrderController(OrderManagementService orderManagementService) {
        this.orderManagementService = orderManagementService;
    }

    @GetMapping
    public ApiResponse<OrderPageResponse> listOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(orderManagementService.listOrders(page, size));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderResponse> getOrder(@PathVariable String orderId) {
        return ApiResponse.ok(orderManagementService.getOrder(orderId));
    }

    @PostMapping
    public ApiResponse<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
        return ApiResponse.ok(orderManagementService.createOrder(request));
    }

    @PutMapping("/{orderId}")
    public ApiResponse<OrderResponse> updateOrder(@PathVariable String orderId,
            @Valid @RequestBody OrderRequest request) {
        return ApiResponse.ok(orderManagementService.updateOrder(orderId, request));
    }

    @DeleteMapping("/{orderId}")
    public ApiResponse<Void> deleteOrder(@PathVariable String orderId) {
        orderManagementService.deleteOrder(orderId);
        return ApiResponse.ok(null);
    }

}
