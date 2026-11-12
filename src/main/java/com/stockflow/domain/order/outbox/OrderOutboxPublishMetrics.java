package com.stockflow.domain.order.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
// 성공·실패 횟수를 기록하는 역할
public class OrderOutboxPublishMetrics {

    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer publishTimer;

    public OrderOutboxPublishMetrics(MeterRegistry registry) {

        // Gauge는 현재 상태를, Counter는 발생 횟수를 측정
        // 애플리케이션 실행 후 발행 성공 횟수(stockflow.outbox.publish.success)
        this.successCounter = Counter.builder(
                        "stockflow.outbox.publish.success"
                )
                .description("Number of successful Outbox publications")
                .register(registry);

        // 애플리케이션 실행 후 발행 실패 횟수(stockflow.outbox.publish.failure)
        this.failureCounter = Counter.builder(
                        "stockflow.outbox.publish.failure"
                )
                .description("Number of failed Outbox publications")
                .register(registry);

        this.publishTimer = Timer.builder(
                        "stockflow.outbox.publish.duration"
                )
                .description("Duration of Outbox publication attempts")
                .register(registry);
    }

    public void recordSuccess() {
        successCounter.increment();
    }

    public void recordFailure() {
        failureCounter.increment();
    }

    // 측정된 시간을 Timer에 기록하는 역할
    public void recordDuration(Duration duration) {
        publishTimer.record(duration);
    }
}