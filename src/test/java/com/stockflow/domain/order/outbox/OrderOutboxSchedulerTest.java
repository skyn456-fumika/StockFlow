package com.stockflow.domain.order.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class OrderOutboxSchedulerTest {

    private OrderOutboxRepository repository;
    private OrderOutboxPublisher publisher;
    private OrderOutboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(OrderOutboxRepository.class);
        publisher = mock(OrderOutboxPublisher.class);

        scheduler = new OrderOutboxScheduler(
                repository,
                publisher
        );
    }

    @Test
    // 실제 RabbitMQ를 실행하지 않는 단위 테스트
    // 스케줄러의 책임인 대기 이벤트 순회와 실패 격리에 집중
    void PENDING_이벤트를_조회하여_발행한다() {

        OrderOutbox first = mock(OrderOutbox.class);
        OrderOutbox second = mock(OrderOutbox.class);

        when(first.getId()).thenReturn(1L);
        when(second.getId()).thenReturn(2L);

        when(repository.findTop20ByStatusOrderByIdAsc(
                OutboxStatus.PENDING
        )).thenReturn(List.of(first, second));

        when(publisher.publishOne(1L)).thenReturn(true);
        when(publisher.publishOne(2L)).thenReturn(true);

        scheduler.publishPendingEvents();

        verify(publisher).publishOne(1L);
        verify(publisher).publishOne(2L);
        verifyNoMoreInteractions(publisher);
    }

    @Test
    void 하나의_이벤트가_실패해도_다음_이벤트를_처리한다() {

        OrderOutbox first = mock(OrderOutbox.class);
        OrderOutbox second = mock(OrderOutbox.class);

        when(first.getId()).thenReturn(1L);
        when(second.getId()).thenReturn(2L);

        when(repository.findTop20ByStatusOrderByIdAsc(
                OutboxStatus.PENDING
        )).thenReturn(List.of(first, second));

        when(publisher.publishOne(1L))
                .thenThrow(new IllegalStateException(
                        "RabbitMQ 발행 실패"
                ));

        when(publisher.publishOne(2L)).thenReturn(true);

        scheduler.publishPendingEvents();

        verify(publisher).publishOne(1L);
        verify(publisher).publishOne(2L);
    }
}