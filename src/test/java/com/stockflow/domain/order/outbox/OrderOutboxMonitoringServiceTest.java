package com.stockflow.domain.order.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
// 집계 로직을 단위 테스트로 검증
class OrderOutboxMonitoringServiceTest {

    private OrderOutboxRepository repository;
    private OrderOutboxMonitoringService service;

    @BeforeEach
    void setUp() {
        repository = mock(OrderOutboxRepository.class);
        service = new OrderOutboxMonitoringService(repository);
    }

    @Test
    // PENDING·READY·DELAYED·PUBLISHED 집계
    void Outbox_상태별_건수를_집계한다() {

        when(repository.countByStatus(OutboxStatus.PENDING))
                .thenReturn(10L);

        when(repository.countByStatus(OutboxStatus.PUBLISHED))
                .thenReturn(30L);

        when(repository.countByStatusAndNextRetryAtAfter(
                eq(OutboxStatus.PENDING),
                any(LocalDateTime.class)
        )).thenReturn(3L);

        OutboxStatusSummary summary = service.getSummary();

        assertThat(summary.pending()).isEqualTo(10);
        assertThat(summary.ready()).isEqualTo(7);
        assertThat(summary.delayed()).isEqualTo(3);
        assertThat(summary.published()).isEqualTo(30);
    }

    @Test
    // 모든 PENDING이 재시도 대기 중일 때 READY = 0
    void 모든_PENDING_이벤트가_재시도_대기중이면_ready는_0이다() {

        when(repository.countByStatus(OutboxStatus.PENDING))
                .thenReturn(5L);

        when(repository.countByStatusAndNextRetryAtAfter(
                eq(OutboxStatus.PENDING),
                any(LocalDateTime.class)
        )).thenReturn(5L);

        OutboxStatusSummary summary = service.getSummary();

        assertThat(summary.pending()).isEqualTo(5);
        assertThat(summary.ready()).isZero();
        assertThat(summary.delayed()).isEqualTo(5);
    }

    @Test
    void 재시도_이력이_있는_이벤트를_조회한다() {

        OrderOutbox outbox = mock(OrderOutbox.class);

        when(outbox.getId()).thenReturn(10L);
        when(outbox.getOrderId()).thenReturn(100L);
        when(outbox.getEventType()).thenReturn("ORDER_CREATED");
        when(outbox.getRetryCount()).thenReturn(2);

        when(repository.findAttentionRequired(
                eq(OutboxStatus.PENDING),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of(outbox));

        List<OutboxAttentionItem> result =
                service.getAttentionRequired();

        assertThat(result).hasSize(1);

        OutboxAttentionItem item = result.get(0);

        assertThat(item.id()).isEqualTo(10L);
        assertThat(item.orderId()).isEqualTo(100L);
        assertThat(item.eventType()).isEqualTo("ORDER_CREATED");
        assertThat(item.retryCount()).isEqualTo(2);

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);

        verify(repository).findAttentionRequired(
                eq(OutboxStatus.PENDING),
                any(LocalDateTime.class),
                pageableCaptor.capture()
        );

        assertThat(pageableCaptor.getValue().getPageSize())
                .isEqualTo(20);
    }

    @Test
    void 관리가_필요한_이벤트가_없으면_빈_목록을_반환한다() {

        when(repository.findAttentionRequired(
                eq(OutboxStatus.PENDING),
                any(LocalDateTime.class),
                any(Pageable.class)
        )).thenReturn(List.of());

        List<OutboxAttentionItem> result =
                service.getAttentionRequired();

        assertThat(result).isEmpty();
    }

    @Test
    void 장애_감지_조건이_없으면_alert는_false다() {

        when(repository.countByStatusAndCreatedAtBefore(
                eq(OutboxStatus.PENDING),
                any(LocalDateTime.class)
        )).thenReturn(0L);

        when(repository.countByStatusAndRetryCountGreaterThanEqual(
                OutboxStatus.PENDING, 5
        )).thenReturn(0L);

        OutboxAlertSummary result = service.getAlertSummary();

        assertThat(result.stalePending()).isZero();
        assertThat(result.highRetry()).isZero();
        assertThat(result.alert()).isFalse();
    }

    @Test
    void 장기_대기_이벤트가_있으면_alert는_true다() {

        when(repository.countByStatusAndCreatedAtBefore(
                eq(OutboxStatus.PENDING),
                any(LocalDateTime.class)
        )).thenReturn(3L);

        OutboxAlertSummary result = service.getAlertSummary();

        assertThat(result.stalePending()).isEqualTo(3);
        assertThat(result.alert()).isTrue();
    }

    @Test
    void 반복_실패_이벤트가_있으면_alert는_true다() {

        when(repository.countByStatusAndRetryCountGreaterThanEqual(
                OutboxStatus.PENDING, 5
        )).thenReturn(2L);

        OutboxAlertSummary result = service.getAlertSummary();

        assertThat(result.highRetry()).isEqualTo(2);
        assertThat(result.alert()).isTrue();
    }
}