package com.example.agentdemo.order;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.order.dto.OrderPageResponse;
import com.example.agentdemo.order.dto.OrderRequest;
import com.example.agentdemo.order.dto.OrderResponse;
import com.example.agentdemo.security.SecurityIdentity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderManagementService {

    private static final String SOURCE = "database:demo_orders";

    private final DemoOrderRepository repository;

    public OrderManagementService(DemoOrderRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public OrderPageResponse listOrders(int page, int size) {
        validatePageRequest(page, size);
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<DemoOrderEntity> orderPage = repository.findAllByOwnerId(SecurityIdentity.currentOwnerId(), pageable);
        List<OrderResponse> content = orderPage.getContent().stream()
                .map(this::toResponse)
                .toList();
        return new OrderPageResponse(content, orderPage.getNumber(), orderPage.getSize(),
                orderPage.getTotalElements(), orderPage.getTotalPages());
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderId) {
        return toResponse(findOrder(orderId));
    }

    @Transactional
    public OrderResponse createOrder(OrderRequest request) {
        if (repository.existsByOrderIdAndOwnerId(request.orderId(), SecurityIdentity.currentOwnerId())) {
            throw new BusinessException("ORDER_ALREADY_EXISTS", "Order already exists: " + request.orderId());
        }
        return toResponse(repository.save(newEntity(request)));
    }

    @Transactional
    public OrderResponse updateOrder(String orderId, OrderRequest request) {
        if (!orderId.equals(request.orderId())) {
            throw new BusinessException("ORDER_ID_MISMATCH", "Path orderId must match request orderId");
        }
        DemoOrderEntity order = findOrder(orderId);
        order.update(request.customerName(), request.status(), request.paid(), request.amount(), request.currency(),
                request.carrier(), request.trackingNumber(), request.estimatedDelivery(), request.latestEvent(),
                request.nextAction());
        return toResponse(repository.save(order));
    }

    @Transactional
    public void deleteOrder(String orderId) {
        String ownerId = SecurityIdentity.currentOwnerId();
        if (!repository.existsByOrderIdAndOwnerId(orderId, ownerId)) {
            throw new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId);
        }
        repository.deleteByOrderIdAndOwnerId(orderId, ownerId);
    }

    private DemoOrderEntity findOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new BusinessException("ORDER_NOT_FOUND", "Order not found");
        }
        return repository.findByOrderIdAndOwnerId(orderId, SecurityIdentity.currentOwnerId())
                .orElseThrow(() -> new BusinessException("ORDER_NOT_FOUND", "Order not found: " + orderId));
    }

    private DemoOrderEntity newEntity(OrderRequest request) {
        return new DemoOrderEntity(request.orderId(), request.customerName(), request.status(), request.paid(),
                request.amount(), request.currency(), request.carrier(), request.trackingNumber(),
                request.estimatedDelivery(), request.latestEvent(), request.nextAction());
    }

    private OrderResponse toResponse(DemoOrderEntity order) {
        return new OrderResponse(order.getOrderId(), order.getCustomerName(), order.getStatus(), order.isPaid(),
                order.getAmount(), order.getCurrency(), order.getCarrier(), order.getTrackingNumber(),
                order.getEstimatedDelivery(), order.getLatestEvent(), order.getNextAction(), SOURCE,
                order.getCreatedAt(), order.getUpdatedAt());
    }

    private void validatePageRequest(int page, int size) {
        if (page < 0) {
            throw new BusinessException("ORDER_QUERY_INVALID", "page must be greater than or equal to 0");
        }
        if (size < 1 || size > 100) {
            throw new BusinessException("ORDER_QUERY_INVALID", "size must be between 1 and 100");
        }
    }

}
