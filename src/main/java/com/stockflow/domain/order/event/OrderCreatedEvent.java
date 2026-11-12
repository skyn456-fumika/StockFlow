package com.stockflow.domain.order.event;

import java.time.LocalDateTime;

public record OrderCreatedEvent(
        Long orderId,
        LocalDateTime occurredAt
) {
}