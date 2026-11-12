package com.stockflow.domain.order.inbox;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "processed_order_events")
public class ProcessedOrderEvent {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    protected ProcessedOrderEvent() {
    }

    public ProcessedOrderEvent(
            UUID eventId,
            Long orderId,
            String eventType
    ) {
        if (eventId == null || orderId == null
                || eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException(
                    "올바른 이벤트 정보가 필요합니다."
            );
        }

        this.eventId = eventId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.processedAt = LocalDateTime.now();
    }

    public UUID getEventId() {
        return eventId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getEventType() {
        return eventType;
    }

    public LocalDateTime getProcessedAt() {
        return processedAt;
    }
}