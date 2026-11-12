package com.stockflow.domain.order.outbox;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderOutboxMonitoringService {

    private final OrderOutboxRepository outboxRepository;

    public OrderOutboxMonitoringService(
            OrderOutboxRepository outboxRepository
    ) {
        this.outboxRepository = outboxRepository;
    }

    @Transactional(readOnly = true)
    public OutboxStatusSummary getSummary() {

        LocalDateTime now = LocalDateTime.now();

        long pending = outboxRepository.countByStatus(
                OutboxStatus.PENDING
        );

        long published = outboxRepository.countByStatus(
                OutboxStatus.PUBLISHED
        );

        long delayed =
                outboxRepository.countByStatusAndNextRetryAtAfter(
                        OutboxStatus.PENDING,
                        now
                );

        // ready = pending - delayed로 계산하는 이유는
        // nextRetryAt이 NULL이거나 이미 지난 이벤트가
        // 모두 발행 가능하기 때문
        long ready = pending - delayed;

        return new OutboxStatusSummary(
                pending,
                ready,
                delayed,
                published
        );
    }

    // 최대 20건만 가져오고, 조회된 엔티티를 DTO로 변환해 반환
    @Transactional(readOnly = true)
    public List<OutboxAttentionItem> getAttentionRequired() {

        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);

        return outboxRepository.findAttentionRequired(
                        OutboxStatus.PENDING,
                        threshold,
                        PageRequest.of(0, 20)
                ).stream()
                .map(outbox -> new OutboxAttentionItem(
                        outbox.getId(),
                        outbox.getEventId(),
                        outbox.getOrderId(),
                        outbox.getEventType(),
                        outbox.getRetryCount(),
                        outbox.getCreatedAt(),
                        outbox.getNextRetryAt()
                ))
                .toList();
    }

    // 두 건수를 단순히 더해서 전체 장애 이벤트 수로 취급하지 않는다
    @Transactional(readOnly = true)
    public OutboxAlertSummary getAlertSummary() {

        LocalDateTime threshold =
                LocalDateTime.now().minusMinutes(15);

        long stalePending =
                outboxRepository.countByStatusAndCreatedAtBefore(
                        OutboxStatus.PENDING,
                        threshold
                );

        long highRetry =
                outboxRepository.countByStatusAndRetryCountGreaterThanEqual(
                        OutboxStatus.PENDING,
                        5
                );

        boolean alert = stalePending > 0 || highRetry > 0;

        return new OutboxAlertSummary(
                stalePending,
                highRetry,
                alert
        );
    }
}