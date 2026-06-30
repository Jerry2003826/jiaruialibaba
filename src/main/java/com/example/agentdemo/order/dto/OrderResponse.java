package com.example.agentdemo.order.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record OrderResponse(
        String orderId,
        String customerName,
        String status,
        boolean paid,
        BigDecimal amount,
        String currency,
        String carrier,
        String trackingNumber,
        LocalDate estimatedDelivery,
        String latestEvent,
        String nextAction,
        String source,
        Instant createdAt,
        Instant updatedAt) {
}
