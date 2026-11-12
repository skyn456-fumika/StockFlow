package com.stockflow.domain.order.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
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

    // PENDING 상태의 이벤트만 조회
    // ID가 작은 순서로 최대 20개 조회
    // 조회한 행에 비관적 락 적용
    // 다른 트랜잭션이 잠근 행은 대기하지 않고 건너뜀
    @Query(
            value = """
                SELECT *
                FROM order_outbox
                WHERE status = 'PENDING'
                ORDER BY id ASC
                LIMIT 20
                FOR UPDATE SKIP LOCKED
                """,
            nativeQuery = true
    )
    List<OrderOutbox> findPendingBatchSkipLocked();

    // 한 번에 이벤트 하나만 잠그고 조회
    // 이벤트 하나의 발행 실패가
    // 전체 배치 트랜잭션을 롤백시키는 문제를 피할 수 있음
    // 즉시 발행 가능한 이벤트 또는 재시도 시각이 지난 이벤트만 선택
    @Query(
            value = """
                SELECT *
                FROM order_outbox
                WHERE status = 'PENDING'
                  AND (
                      next_retry_at IS NULL
                      OR next_retry_at <= CURRENT_TIMESTAMP
                  )
                ORDER BY id ASC
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """,
            nativeQuery = true
    )
    Optional<OrderOutbox> findFirstPendingSkipLocked();

    long countByStatus(OutboxStatus status);

    long countByStatusAndNextRetryAtAfter(
            OutboxStatus status,
            LocalDateTime now
    );

    // PENDING 중에서 재시도 이력이 있거나 오래 대기한 이벤트를 생성 시각순으로 조회하는 쿼리
    @Query("""
    SELECT o
    FROM OrderOutbox o
    WHERE o.status = :status
      AND (
          o.retryCount > 0
          OR o.createdAt < :threshold
      )
    ORDER BY o.createdAt ASC, o.id ASC
    """)
    List<OrderOutbox> findAttentionRequired(
            @Param("status") OutboxStatus status,
            @Param("threshold") LocalDateTime threshold,
            Pageable pageable
    );

    // 오래 대기한 이벤트의 개수를 조회
    long countByStatusAndCreatedAtBefore(
            OutboxStatus status,
            LocalDateTime threshold
    );

    // 반복 실패한 이벤트의 개수를 조회
    long countByStatusAndRetryCountGreaterThanEqual(
            OutboxStatus status,
            int retryCount
    );
}