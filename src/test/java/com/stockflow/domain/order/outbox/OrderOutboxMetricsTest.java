package com.stockflow.domain.order.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class OrderOutboxMetricsTest {

    private OrderOutboxMonitoringService monitoringService;
    // Docker나 Spring Boot 전체 실행 없이도 Gauge 동작을 확인할 수 있음
    private SimpleMeterRegistry registry;
    private OrderOutboxMetrics metrics;

    @BeforeEach
    void setUp() {

        monitoringService = mock(OrderOutboxMonitoringService.class);
        registry = new SimpleMeterRegistry();

        metrics = new OrderOutboxMetrics(
                monitoringService,
                registry
        );
    }

    @Test
    void Outbox_메트릭을_등록한다() {

        assertThat(registry.find("stockflow.outbox.pending").gauge())
                .isNotNull();

        assertThat(registry.find("stockflow.outbox.ready").gauge())
                .isNotNull();

        assertThat(registry.find("stockflow.outbox.delayed").gauge())
                .isNotNull();

        assertThat(registry.find("stockflow.outbox.published").gauge())
                .isNotNull();
    }

    @Test
    void DB_집계_결과를_Gauge에_반영한다() {

        when(monitoringService.getSummary())
                .thenReturn(new OutboxStatusSummary(
                        10, 7, 3, 30
                ));

        metrics.refresh();

        assertThat(registry.get("stockflow.outbox.pending")
                .gauge().value()).isEqualTo(10);

        assertThat(registry.get("stockflow.outbox.ready")
                .gauge().value()).isEqualTo(7);

        assertThat(registry.get("stockflow.outbox.delayed")
                .gauge().value()).isEqualTo(3);

        assertThat(registry.get("stockflow.outbox.published")
                .gauge().value()).isEqualTo(30);
    }

    @Test
    void 집계값이_변경되면_Gauge도_갱신된다() {

        when(monitoringService.getSummary())
                .thenReturn(
                        new OutboxStatusSummary(10, 7, 3, 30),
                        new OutboxStatusSummary(5, 4, 1, 35)
                );

        metrics.refresh();
        metrics.refresh();

        assertThat(registry.get("stockflow.outbox.pending")
                .gauge().value()).isEqualTo(5);

        assertThat(registry.get("stockflow.outbox.delayed")
                .gauge().value()).isEqualTo(1);

        assertThat(registry.get("stockflow.outbox.published")
                .gauge().value()).isEqualTo(35);

        verify(monitoringService, times(2)).getSummary();
    }
}