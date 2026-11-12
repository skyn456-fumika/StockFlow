package com.stockflow.domain.order.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class OrderOutboxSchedulerTest {

    private OrderOutboxBatchPublisher batchPublisher;
    private OrderOutboxRetryService retryService;
    private OrderOutboxPublishMetrics publishMetrics;
    private OrderOutboxScheduler scheduler;

    @BeforeEach
    void setUp() {
        batchPublisher = mock(OrderOutboxBatchPublisher.class);
        retryService = mock(OrderOutboxRetryService.class);
        publishMetrics = mock(OrderOutboxPublishMetrics.class);

        scheduler = new OrderOutboxScheduler(
                batchPublisher,
                retryService,
                publishMetrics
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

    @Test
    // 발행 3건 성공 후 더 이상 발행할 이벤트가 없으면 성공 Counter가 정확히 세 번 증가
    void 발행_성공한_횟수만큼_Counter가_증가한다() {

        when(batchPublisher.publishNext())
                .thenReturn(true, true, true, false);

        scheduler.publishPendingEvents();

        verify(publishMetrics, times(3))
                .recordSuccess();

        verify(publishMetrics, never())
                .recordFailure();

        verifyNoInteractions(retryService);
    }

    @Test
    // 최초 발행이 실패하더라도 다음 이벤트를 처리하고, 성공과 실패가 각각 한 번씩 기록되는지 확인
    void 발행_실패와_이후_성공을_각각_Counter에_기록한다() {

        when(batchPublisher.publishNext())
                .thenThrow(new OutboxPublishException(
                        100L,
                        new IllegalStateException("RabbitMQ 장애")
                ))
                .thenReturn(true, false);

        scheduler.publishPendingEvents();

        // 최초 발행 실패
        verify(publishMetrics, times(1))
                .recordFailure();

        // 이후 다른 이벤트 정상 발행
        verify(publishMetrics, times(1))
                .recordSuccess();

        // 실패한 이벤트 재시도 예약
        verify(retryService, times(1))
                .recordFailure(100L);

        verify(batchPublisher, times(3))
                .publishNext();
    }

    @Test
    void 발행_성공시_Timer에_처리시간을_기록한다() {

        when(batchPublisher.publishNext())
                .thenReturn(true, true, false);

        scheduler.publishPendingEvents();

        ArgumentCaptor<Duration> captor =
                ArgumentCaptor.forClass(Duration.class);

        verify(publishMetrics, times(2))
                .recordDuration(captor.capture());

        assertThat(captor.getAllValues())
                .hasSize(2)
                .allSatisfy(duration ->
                        assertThat(duration)
                                .isGreaterThanOrEqualTo(Duration.ZERO)
                );

        verify(publishMetrics, times(2))
                .recordSuccess();
    }

    @Test
    void 발행_실패시에도_Timer에_처리시간을_기록한다() {

        when(batchPublisher.publishNext())
                .thenThrow(new OutboxPublishException(
                        100L,
                        new IllegalStateException("RabbitMQ 장애")
                ))
                .thenReturn(false);

        scheduler.publishPendingEvents();

        ArgumentCaptor<Duration> captor =
                ArgumentCaptor.forClass(Duration.class);

        verify(publishMetrics, times(1))
                .recordDuration(captor.capture());

        assertThat(captor.getValue())
                .isGreaterThanOrEqualTo(Duration.ZERO);

        verify(publishMetrics).recordFailure();
        verify(retryService).recordFailure(100L);

        verify(publishMetrics, never())
                .recordSuccess();
    }

    @Test
    void 발행할_이벤트가_없으면_Timer를_기록하지_않는다() {

        when(batchPublisher.publishNext())
                .thenReturn(false);

        scheduler.publishPendingEvents();

        verify(publishMetrics, never())
                .recordDuration(any(Duration.class));

        verify(publishMetrics, never())
                .recordSuccess();

        verify(publishMetrics, never())
                .recordFailure();
    }
}