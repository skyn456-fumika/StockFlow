package com.stockflow.domain.order.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
// 트랜잭션에서 실패 정보를 저장하는 서비스
public class OrderOutboxRetryService {

    private final OrderOutboxRepository outboxRepository;

    public OrderOutboxRetryService(
            OrderOutboxRepository outboxRepository
    ) {
        this.outboxRepository = outboxRepository;
    }

    // REQUIRES_NEW는 기존 트랜잭션과 독립된 새로운 트랜잭션을 사용
    // 발행 실패 트랜잭션이 롤백된 뒤 이 서비스를 호출하면, 재시도 정보는 별도로 커밋 가능
    // 단, 새로운 트랜잭션이라도 기존 발행 트랜잭션이 락을 해제한 이후에 호출해야 함
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long outboxId) {

        OrderOutbox outbox = outboxRepository
                .findByIdForUpdate(outboxId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Outbox 이벤트를 찾을 수 없습니다."
                        )
                );

        // 이미 발행된 이벤트는 실패 횟수를 변경하지 않음
        if (outbox.getStatus() == OutboxStatus.PUBLISHED) {
            return;
        }

        outbox.scheduleRetry(LocalDateTime.now());
    }
}