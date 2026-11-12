package com.stockflow.domain.order.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "stockflow.outbox",
        name = "scheduling-enabled",
        havingValue = "true"
)
public class OrderOutboxSchedulingJob {

    private final OrderOutboxScheduler scheduler;

    public OrderOutboxSchedulingJob(OrderOutboxScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Scheduled(
            fixedDelayString = "${stockflow.outbox.poll-delay-ms:5000}",
            initialDelayString = "${stockflow.outbox.initial-delay-ms:5000}"
    )
    public void run() {
        scheduler.publishPendingEvents();
    }
}