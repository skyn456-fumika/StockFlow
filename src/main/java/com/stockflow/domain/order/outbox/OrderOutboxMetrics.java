package com.stockflow.domain.order.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
@ConditionalOnProperty(
        prefix = "stockflow.metrics.outbox",
        name = "enabled",
        havingValue = "true"
)
// Outbox 메트릭 구현
public class OrderOutboxMetrics {

    private final OrderOutboxMonitoringService monitoringService;

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong ready = new AtomicLong();
    private final AtomicLong delayed = new AtomicLong();
    private final AtomicLong published = new AtomicLong();

    public OrderOutboxMetrics(
            OrderOutboxMonitoringService monitoringService,
            MeterRegistry registry
    ) {
        this.monitoringService = monitoringService;

        // Gauge는 현재 상태를 표현하는 메트릭이므로 Outbox 대기 건수에 적합
        // 발행 대기 이벤트 수
        Gauge.builder("stockflow.outbox.pending", pending, AtomicLong::get)
                .register(registry);

        // 즉시 발행 가능한 이벤트 수
        Gauge.builder("stockflow.outbox.ready", ready, AtomicLong::get)
                .register(registry);

        // 	재시도 대기 이벤트 수
        Gauge.builder("stockflow.outbox.delayed", delayed, AtomicLong::get)
                .register(registry);

        // 발행 완료 이벤트 수
        Gauge.builder("stockflow.outbox.published", published, AtomicLong::get)
                .register(registry);
    }

    @Scheduled(
            fixedDelayString = "${stockflow.metrics.outbox.refresh-ms:30000}",
            initialDelayString = "${stockflow.metrics.outbox.initial-delay-ms:5000}"
    )
    public void refresh() {

        OutboxStatusSummary summary = monitoringService.getSummary();

        pending.set(summary.pending());
        ready.set(summary.ready());
        delayed.set(summary.delayed());
        published.set(summary.published());
    }
}