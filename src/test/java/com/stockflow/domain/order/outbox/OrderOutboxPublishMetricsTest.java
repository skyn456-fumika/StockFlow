package com.stockflow.domain.order.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OrderOutboxPublishMetricsTest {

    private SimpleMeterRegistry registry;
    private OrderOutboxPublishMetrics metrics;

    @BeforeEach
    void setUp() {

        registry = new SimpleMeterRegistry();

        metrics = new OrderOutboxPublishMetrics(registry);
    }

    @Test
    void 성공과_실패_Counter를_등록한다() {

        assertThat(
                registry.find(
                        "stockflow.outbox.publish.success"
                ).counter()
        ).isNotNull();

        assertThat(
                registry.find(
                        "stockflow.outbox.publish.failure"
                ).counter()
        ).isNotNull();
    }

    @Test
    void 발행_성공시_성공_Counter가_증가한다() {

        metrics.recordSuccess();
        metrics.recordSuccess();
        metrics.recordSuccess();

        assertThat(
                registry.get(
                        "stockflow.outbox.publish.success"
                ).counter().count()
        ).isEqualTo(3);

        assertThat(
                registry.get(
                        "stockflow.outbox.publish.failure"
                ).counter().count()
        ).isZero();
    }

    @Test
    void 발행_실패시_실패_Counter가_증가한다() {

        metrics.recordFailure();
        metrics.recordFailure();

        assertThat(
                registry.get(
                        "stockflow.outbox.publish.failure"
                ).counter().count()
        ).isEqualTo(2);

        assertThat(
                registry.get(
                        "stockflow.outbox.publish.success"
                ).counter().count()
        ).isZero();
    }

    @Test
    void 성공과_실패는_독립적으로_집계된다() {

        metrics.recordSuccess();
        metrics.recordFailure();
        metrics.recordSuccess();

        assertThat(
                registry.get(
                        "stockflow.outbox.publish.success"
                ).counter().count()
        ).isEqualTo(2);

        assertThat(
                registry.get(
                        "stockflow.outbox.publish.failure"
                ).counter().count()
        ).isEqualTo(1);
    }

    @Test
    void 발행_처리시간_Timer를_등록한다() {

        assertThat(
                registry.find(
                        "stockflow.outbox.publish.duration"
                ).timer()
        ).isNotNull();
    }

    @Test
    void 발행_처리시간을_Timer에_기록한다() {

        metrics.recordDuration(Duration.ofMillis(150));

        var timer = registry.get(
                "stockflow.outbox.publish.duration"
        ).timer();

        assertThat(timer.count()).isEqualTo(1);

        assertThat(timer.totalTime(TimeUnit.MILLISECONDS))
                .isCloseTo(150.0, within(0.001));
    }

    @Test
    void 여러_발행_처리시간을_누적한다() {

        metrics.recordDuration(Duration.ofMillis(100));
        metrics.recordDuration(Duration.ofMillis(200));
        metrics.recordDuration(Duration.ofMillis(300));

        var timer = registry.get(
                "stockflow.outbox.publish.duration"
        ).timer();

        assertThat(timer.count()).isEqualTo(3);

        assertThat(timer.totalTime(TimeUnit.MILLISECONDS))
                .isCloseTo(600.0, within(0.001));

        assertThat(timer.mean(TimeUnit.MILLISECONDS))
                .isCloseTo(200.0, within(0.001));
    }
}