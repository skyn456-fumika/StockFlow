package com.stockflow.domain.order.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderOutboxRepository
        extends JpaRepository<OrderOutbox, Long> {

    // PENDING 이벤트를 조회할 때 활용
    List<OrderOutbox> findByStatusOrderByIdAsc(
            OutboxStatus status
    );

    // 조회 메서드
    List<OrderOutbox> findByOrderIdOrderByIdAsc(Long orderId);

    // 여러 발행기가 같은 이벤트를 동시에 처리할 때
    // 중복 발행 위험을 줄이기 위한 잠금
    // 다만 발행 후 DB 트랜잭션이 실패하면 재발행될 수 있으므로,
    // 이것만으로 중복 전달이 완전히 방지되는 것은 아님
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT o
    FROM OrderOutbox o
    WHERE o.id = :id
    """)
    Optional<OrderOutbox> findByIdForUpdate(
            @Param("id") Long id
    );

    // 조회 결과를 최대 20개로 제한
    List<OrderOutbox> findTop20ByStatusOrderByIdAsc(
            OutboxStatus status
    );
}