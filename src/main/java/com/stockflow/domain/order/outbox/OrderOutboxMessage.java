package com.stockflow.domain.order.outbox;

import java.time.LocalDateTime;
import java.util.UUID;

public record OrderOutboxMessage(
        UUID eventId,               // Consumer가 이미 처리한 이벤트인지 식별할 수 있도록 하기 위해서
        Long orderId,
        String eventType,
        LocalDateTime occurredAt
) {
}