package com.stockflow.domain.order.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class OrderOutboxTest {

    @Test
    void Outbox_생성시_PENDING_상태로_시작한다() {

        OrderOutbox outbox = new OrderOutbox(
                1L,
                "ORDER_CREATED",
                "{\"orderId\":1}"
        );

        assertThat(outbox.getEventId()).isNotNull();
        assertThat(outbox.getOrderId()).isEqualTo(1L);
        assertThat(outbox.getStatus())
                .isEqualTo(OutboxStatus.PENDING);
        assertThat(outbox.getCreatedAt()).isNotNull();
        assertThat(outbox.getPublishedAt()).isNull();
    }

    @Test
    void 발행_완료_처리시_PUBLISHED로_변경된다() {

        OrderOutbox outbox = new OrderOutbox(
                1L,
                "ORDER_CREATED",
                "{\"orderId\":1}"
        );

        outbox.markPublished();

        assertThat(outbox.getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);

        assertThat(outbox.getPublishedAt()).isNotNull();
    }

    @Test
    void 이미_발행된_이벤트는_다시_완료_처리할_수_없다() {

        OrderOutbox outbox = new OrderOutbox(
                1L,
                "ORDER_CREATED",
                "{\"orderId\":1}"
        );

        outbox.markPublished();

        assertThatThrownBy(outbox::markPublished)
                .isInstanceOf(IllegalStateException.class);
    }
}