package com.stockflow.domain.order.outbox;

public record OutboxStatusSummary(
        long pending,
        long ready,
        long delayed,
        long published
) {
}