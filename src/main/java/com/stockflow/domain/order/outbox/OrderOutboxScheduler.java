package com.stockflow.domain.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderOutboxScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(OrderOutboxScheduler.class);

    private final OrderOutboxRepository outboxRepository;
    private final OrderOutboxPublisher publisher;

    public OrderOutboxScheduler(
            OrderOutboxRepository outboxRepository,
            OrderOutboxPublisher publisher
    ) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
    }

    @Scheduled(
            // 한 번의 실행이 끝난 다음 5초가 지나면 다음 실행을 시작
            fixedDelayString = "${stockflow.outbox.poll-delay-ms:5000}",
            initialDelayString = "${stockflow.outbox.initial-delay-ms:5000}"
    )
    public void publishPendingEvents() {

        log.info("Outbox 스케줄러 실행");

        List<OrderOutbox> pendingEvents =
                outboxRepository.findTop20ByStatusOrderByIdAsc(
                        OutboxStatus.PENDING
                );

        log.info("발행 대기 이벤트 수: {}", pendingEvents.size());

        for (OrderOutbox event : pendingEvents) {
            try {
                // publishOne()의 @Transactional이 이벤트별로 트랜잭션 적용
                // 따라서 하나의 이벤트 발행이 실패해도 다른 이벤트의 트랜잭션에는 영향을 주지 않음
                boolean published = publisher.publishOne(event.getId());

                if (published) {
                    log.info(
                            "Outbox 이벤트 발행 완료. eventId={}",
                            event.getEventId()
                    );
                }

            } catch (Exception e) {
                // 발행 실패 이벤트는 PENDING 상태로 유지되므로
                // 다음 스케줄 실행에서 다시 시도할 수 있음
                log.error(
                        "Outbox 이벤트 발행 실패. outboxId={}",
                        event.getId(),
                        e
                );
            }
        }
    }
}