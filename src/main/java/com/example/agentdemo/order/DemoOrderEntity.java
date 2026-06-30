package com.example.agentdemo.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "demo_orders")
public class DemoOrderEntity {

    @Id
    @Column(length = 64)
    private String orderId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(nullable = false)
    private boolean paid;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(length = 64)
    private String customerName;

    @Column(length = 64)
    private String carrier;

    @Column(length = 64)
    private String trackingNumber;

    private LocalDate estimatedDelivery;

    @Column(length = 512)
    private String latestEvent;

    @Column(length = 512)
    private String nextAction;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected DemoOrderEntity() {
    }

    public DemoOrderEntity(String orderId, String status, boolean paid, BigDecimal amount, String currency,
            String carrier, String trackingNumber, LocalDate estimatedDelivery, String latestEvent,
            String nextAction) {
        this(orderId, null, status, paid, amount, currency, carrier, trackingNumber, estimatedDelivery,
                latestEvent, nextAction);
    }

    public DemoOrderEntity(String orderId, String customerName, String status, boolean paid, BigDecimal amount,
            String currency, String carrier, String trackingNumber, LocalDate estimatedDelivery, String latestEvent,
            String nextAction) {
        this.orderId = orderId;
        update(customerName, status, paid, amount, currency, carrier, trackingNumber, estimatedDelivery, latestEvent,
                nextAction);
    }

    public void update(String customerName, String status, boolean paid, BigDecimal amount, String currency,
            String carrier, String trackingNumber, LocalDate estimatedDelivery, String latestEvent,
            String nextAction) {
        this.customerName = customerName;
        this.status = status;
        this.paid = paid;
        this.amount = amount;
        this.currency = currency;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.estimatedDelivery = estimatedDelivery;
        this.latestEvent = latestEvent;
        this.nextAction = nextAction;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getOrderId() {
        return orderId;
    }

    public String getStatus() {
        return status;
    }

    public String getCustomerName() {
        return customerName;
    }

    public boolean isPaid() {
        return paid;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCarrier() {
        return carrier;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public LocalDate getEstimatedDelivery() {
        return estimatedDelivery;
    }

    public String getLatestEvent() {
        return latestEvent;
    }

    public String getNextAction() {
        return nextAction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

}
