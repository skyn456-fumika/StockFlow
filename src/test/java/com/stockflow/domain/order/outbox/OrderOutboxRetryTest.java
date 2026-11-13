package com.stockflow.domain.order.outbox;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

// Outbox 재시도 정보 테스트
@org.junit.jupiter.api.Tag("unit")
class OrderOutboxRetryTest {

    private OrderOutbox createOutbox() {
        return new OrderOutbox(
                1L,
                "ORDER_CREATED",
                "{\"orderId\":1}"
        );
    }

    @Test
    void 최초_발행_실패시_5초후_재시도하도록_설정한다() {

        OrderOutbox outbox = createOutbox();

        LocalDateTime failedAt =
                LocalDateTime.of(2026, 11, 12, 15, 0);

        outbox.scheduleRetry(failedAt);

        assertThat(outbox.getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        assertThat(outbox.getRetryCount())
                .isEqualTo(1);

        assertThat(outbox.getNextRetryAt())
                .isEqualTo(failedAt.plusSeconds(5));
    }

    @Test
    void 반복_실패하면_재시도_대기시간이_증가한다() {

        OrderOutbox outbox = createOutbox();

        LocalDateTime failedAt =
                LocalDateTime.of(2026, 11, 12, 15, 0);

        outbox.scheduleRetry(failedAt);

        assertThat(outbox.getNextRetryAt())
                .isEqualTo(failedAt.plusSeconds(5));

        outbox.scheduleRetry(failedAt);

        assertThat(outbox.getNextRetryAt())
                .isEqualTo(failedAt.plusSeconds(10));

        outbox.scheduleRetry(failedAt);

        assertThat(outbox.getNextRetryAt())
                .isEqualTo(failedAt.plusSeconds(20));

        assertThat(outbox.getRetryCount())
                .isEqualTo(3);
    }

    @Test
    void 재시도_대기시간은_최대_300초를_넘지_않는다() {

        OrderOutbox outbox = createOutbox();

        LocalDateTime failedAt =
                LocalDateTime.of(2026, 11, 12, 15, 0);

        for (int i = 0; i < 10; i++) {
            outbox.scheduleRetry(failedAt);
        }

        assertThat(outbox.getRetryCount())
                .isEqualTo(10);

        assertThat(outbox.getNextRetryAt())
                .isEqualTo(failedAt.plusSeconds(300));
    }

    @Test
    void 발행에_성공하면_재시도_시각을_초기화한다() {

        OrderOutbox outbox = createOutbox();

        outbox.scheduleRetry(LocalDateTime.now());

        outbox.markPublished();

        assertThat(outbox.getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);

        assertThat(outbox.getNextRetryAt())
                .isNull();

        assertThat(outbox.getRetryCount())
                .isEqualTo(1);
    }

    @Test
    void 발행이_완료된_이벤트는_재시도할_수_없다() {

        OrderOutbox outbox = createOutbox();

        outbox.markPublished();

        assertThatThrownBy(
                () -> outbox.scheduleRetry(LocalDateTime.now())
        ).isInstanceOf(IllegalStateException.class);
    }
}