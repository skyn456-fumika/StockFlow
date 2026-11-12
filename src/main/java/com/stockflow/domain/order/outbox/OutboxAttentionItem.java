package com.stockflow.domain.order.outbox;

import java.time.LocalDateTime;
import java.util.UUID;

// 관리자 API의 JSON 응답으로도 사용
public record OutboxAttentionItem(
        Long id,
        UUID eventId,
        Long orderId,
        String eventType,
        int retryCount,
        LocalDateTime createdAt,
        LocalDateTime nextRetryAt
) {
}