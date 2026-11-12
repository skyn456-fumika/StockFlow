package com.stockflow.domain.order.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class OrderOutboxBatchPublisher {

    private final OrderOutboxRepository outboxRepository;
    private final OrderOutboxPublisher publisher;

    public OrderOutboxBatchPublisher(
            OrderOutboxRepository outboxRepository,
            OrderOutboxPublisher publisher
    ) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
    }

    // 예외를 외부로 전달하면 트랜잭션이 롤백되고 행 잠금이 해제
    // 그 후 스케줄러에서 재시도 정보를 저장
    @Transactional
//    1. DB 트랜잭션 시작
//    2. SKIP LOCKED로 발행 가능한 이벤트 1건 조회
//    3. RabbitMQ 발행 및 라우팅 확인
//    4. Outbox 상태를 PUBLISHED로 변경
//    5. DB 트랜잭션 커밋 및 행 잠금 해제
    // 여러 인스턴스가 동시에 publishNext()를 호출해도
    // 서로 다른 이벤트를 선택할 수 있게 됨
    public boolean publishNext() {

        Optional<OrderOutbox> pending =
                outboxRepository.findFirstPendingSkipLocked();

        if (pending.isEmpty()) {
            return false;
        }

        OrderOutbox outbox = pending.get();

        try {
            publisher.publishLocked(outbox);
            return true;

        } catch (RuntimeException e) {
            throw new OutboxPublishException(outbox.getId(), e);
        }
    }
}