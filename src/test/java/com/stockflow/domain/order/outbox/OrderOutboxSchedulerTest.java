package com.stockflow.domain.order.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class OrderOutboxSchedulerTest {

    private OrderOutboxBatchPublisher batchPublisher;
    private OrderOutboxRetryService retryService;
    private OrderOutboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        batchPublisher = mock(OrderOutboxBatchPublisher.class);
        retryService = mock(OrderOutboxRetryService.class);

        scheduler = new OrderOutboxScheduler(
                batchPublisher,
                retryService
        );
    }

    @Test
    void PENDING_이벤트를_모두_발행한다() {

        // 3건 발행 성공, 이후 대기 이벤트 없음
        when(batchPublisher.publishNext())
                .thenReturn(true, true, true, false);

        scheduler.publishPendingEvents();

        verify(batchPublisher, times(4))
                .publishNext();
    }

    @Test
    void 발행할_이벤트가_없으면_즉시_종료한다() {

        when(batchPublisher.publishNext())
                .thenReturn(false);

        scheduler.publishPendingEvents();

        verify(batchPublisher, times(1))
                .publishNext();
    }

    @Test
    void 한번에_최대_20개까지만_발행한다() {

        when(batchPublisher.publishNext())
                .thenReturn(true);

        scheduler.publishPendingEvents();

        verify(batchPublisher, times(20))
                .publishNext();
    }

    @Test
    void 발행에_실패하면_이번_스케줄_실행을_종료한다() {

        when(batchPublisher.publishNext())
                .thenReturn(true)
                .thenThrow(new IllegalStateException(
                        "RabbitMQ 발행 실패"
                ));

        scheduler.publishPendingEvents();

        verify(batchPublisher, times(2))
                .publishNext();
    }

    @Test
    void 발행_실패시_재시도를_예약하고_다음_이벤트를_처리한다() {

        when(batchPublisher.publishNext())
                .thenThrow(new OutboxPublishException(
                        1L,
                        new IllegalStateException("RabbitMQ 장애")
                ))
                .thenReturn(true, false);

        scheduler.publishPendingEvents();

        verify(retryService).recordFailure(1L);

        verify(batchPublisher, times(3))
                .publishNext();
    }
}