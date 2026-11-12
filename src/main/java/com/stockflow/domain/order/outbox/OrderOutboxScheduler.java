package com.stockflow.domain.order.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OrderOutboxScheduler {

    private static final Logger log =
            LoggerFactory.getLogger(OrderOutboxScheduler.class);

    private static final int BATCH_SIZE = 20;

    private final OrderOutboxBatchPublisher batchPublisher;
    private final OrderOutboxRetryService retryService;
    private final OrderOutboxPublishMetrics publishMetrics;

    public OrderOutboxScheduler(
            OrderOutboxBatchPublisher batchPublisher,
            OrderOutboxRetryService retryService,
            OrderOutboxPublishMetrics publishMetrics
    ) {
        this.batchPublisher = batchPublisher;
        this.retryService = retryService;
        this.publishMetrics = publishMetrics;
    }

    public void publishPendingEvents() {

        int publishedCount = 0;

        for (int i = 0; i < BATCH_SIZE; i++) {

            // 경과 시간 측정에 적합(System.nanoTime())
            // LocalDateTime.now()와 달리 시스템 시계 변경에 따른 영향을 피할 수 있음
            long startNanos = System.nanoTime();

            try {
                boolean published = batchPublisher.publishNext();

                if (!published) {
                    break;
                }

                // 발행 및 DB 트랜잭션 완료까지 걸린 시간
                publishMetrics.recordDuration(
                        Duration.ofNanos(
                                System.nanoTime() - startNanos
                        )
                );

                publishMetrics.recordSuccess();
                publishedCount++;

            } catch (OutboxPublishException e) {

                // 실패한 발행 시도도 Timer에 기록
                publishMetrics.recordDuration(
                        Duration.ofNanos(
                                System.nanoTime() - startNanos
                        )
                );

                publishMetrics.recordFailure();

                log.warn(
                        "Outbox 발행 실패. 재시도 예약. outboxId={}",
                        e.getOutboxId(),
                        e
                );

                try {
                    retryService.recordFailure(e.getOutboxId());

                    log.info(
                            "Outbox 재시도 예약 저장 완료. outboxId={}",
                            e.getOutboxId()
                    );

                } catch (Exception retryError) {

                    log.error(
                            "Outbox 재시도 정보 저장 실패. outboxId={}",
                            e.getOutboxId(),
                            retryError
                    );

                    break;
                }

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