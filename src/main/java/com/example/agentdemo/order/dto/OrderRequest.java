package com.example.agentdemo.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OrderRequest(
        @NotBlank @Pattern(regexp = "\\d{8,64}", message = "must be a numeric id with at least 8 digits") String orderId,
        @Size(max = 64) String customerName,
        @NotBlank @Size(max = 32) String status,
        @NotNull Boolean paid,
        @NotNull @DecimalMin("0.00") BigDecimal amount,
        @NotBlank @Size(max = 8) String currency,
        @Size(max = 64) String carrier,
        @Size(max = 64) String trackingNumber,
        LocalDate estimatedDelivery,
        @Size(max = 512) String latestEvent,
        @Size(max = 512) String nextAction) {
}
