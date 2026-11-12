package com.stockflow.domain.order.outbox;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "order_outbox")
public class OrderOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    protected OrderOutbox() {
    }

    public OrderOutbox(
            Long orderId,
            String eventType,
            String payload
    ) {
        this(UUID.randomUUID(), orderId, eventType, payload);
    }

    // DB에 저장하는 eventId와
    // JSON 메시지에 들어가는 eventId를 일치시키기 위한 생성자
    public OrderOutbox(
            UUID eventId,
            Long orderId,
            String eventType,
            String payload
    ) {
        if (eventId == null
                || orderId == null
                || eventType == null
                || eventType.isBlank()
                || payload == null
                || payload.isBlank()) {
            throw new IllegalArgumentException(
                    "올바른 Outbox 이벤트 정보가 필요합니다."
            );
        }

        this.eventId = eventId;
        this.orderId = orderId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    // RabbitMQ 발행 성공이 확인됐을 때 호출할 메서드
    public void markPublished() {
        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException(
                    "이미 발행 완료된 이벤트입니다."
            );
        }

        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        nextRetryAt = null;
    }

    public void scheduleRetry(LocalDateTime failedAt) {

        if (status != OutboxStatus.PENDING) {
            throw new IllegalStateException(
                    "발행 완료된 이벤트는 재시도할 수 없습니다."
            );
        }

        if (failedAt == null) {
            throw new IllegalArgumentException(
                    "실패 시각은 필수입니다."
            );
        }

        retryCount++;

        // 첫 실패 5초, 이후 2배씩 증가, 최대 300초
        int exponent = Math.min(retryCount - 1, 6);

        long delaySeconds = Math.min(
                5L * (1L << exponent),
                300L
        );

        nextRetryAt = failedAt.plusSeconds(delaySeconds);
    }

    public int getRetryCount() {
        return retryCount;
    }

    public LocalDateTime getNextRetryAt() {
        return nextRetryAt;
    }

    public Long getId() {
        return id;
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

    public String getPayload() {
        return payload;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }
}