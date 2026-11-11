package com.stockflow.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    // 주문을 조회할 때 연관된 주문 상품과 상품 정보까지 함께 로딩하도록 지정하는 기능
    @EntityGraph(attributePaths = {"items", "items.product"})
    Optional<Order> findWithItemsById(Long id);

    // 비관적 락 조회
    // 해당 주문 행을 잠그므로,
    // 동일 주문에 대한 취소 요청이 동시에 들어왔을 때 순차적으로 처리
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT o
    FROM Order o
    WHERE o.id = :orderId
    """)
    Optional<Order> findByIdForUpdate(
            @Param("orderId") Long orderId
    );

}