package com.stockflow.domain.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OrderOutboxScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(OrderOutboxScheduler.class);

    private static final int BATCH_SIZE = 20;

    private final OrderOutboxBatchPublisher batchPublisher;
    private final OrderOutboxRetryService retryService;

    public OrderOutboxScheduler(
            OrderOutboxBatchPublisher batchPublisher,
            OrderOutboxRetryService retryService
    ) {
        this.batchPublisher = batchPublisher;
        this.retryService = retryService;
    }

    @Scheduled(
            fixedDelayString = "${stockflow.outbox.poll-delay-ms:5000}",
            initialDelayString = "${stockflow.outbox.initial-delay-ms:5000}"
    )
    public void publishPendingEvents() {

        int publishedCount = 0;

        for (int i = 0; i < BATCH_SIZE; i++) {

            try {
                boolean published = batchPublisher.publishNext();

                if (!published) {
                    break;
                }

                publishedCount++;

            } catch (OutboxPublishException e) {

                log.warn(
                        "Outbox 발행 실패. 재시도 예약. outboxId={}",
                        e.getOutboxId(),
                        e
                );

                try {
                    // 발행 트랜잭션 롤백 이후 별도 트랜잭션으로 저장
                    retryService.recordFailure(e.getOutboxId());

                } catch (Exception retryError) {
                    log.error(
                            "Outbox 재시도 정보 저장 실패. outboxId={}",
                            e.getOutboxId(),
                            retryError
                    );

                    break;
                }

                // 실패 이벤트는 재시도 시각까지 조회되지 않으므로
                // 다른 이벤트 처리 계속 진행

            } catch (Exception e) {

                log.error(
                        "Outbox 배치 처리 중 예상하지 못한 오류",
                        e
                );

                break;
            }
        }

        if (publishedCount > 0) {
            log.info(
                    "Outbox 배치 발행 완료. publishedCount={}",
                    publishedCount
            );
        }
    }
}