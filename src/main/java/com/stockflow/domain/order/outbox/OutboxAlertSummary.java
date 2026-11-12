package com.stockflow.domain.order.outbox;

public record OutboxAlertSummary(
        long stalePending,
        long highRetry,
        boolean alert       // alert는 운영자 확인이 필요한 상황인지 나타내는 값
) {
}