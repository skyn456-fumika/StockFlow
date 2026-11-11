package com.stockflow.domain.inventory;

import com.stockflow.domain.product.dto.ProductResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface InventoryRepository
        extends JpaRepository<Inventory, Long> {

    // 일반 재고 조회
    Optional<Inventory> findByProductId(Long productId);

    // 재고와 상품을 연결하여 조회
    @Query(
            value = """
        SELECT new com.stockflow.domain.product.dto.ProductResponse(
            p.id, p.name, p.price, p.status, i.quantity
        )
        FROM Inventory i
        JOIN i.product p
        """,
            countQuery = "SELECT COUNT(i) FROM Inventory i"
    )
    Page<ProductResponse> findProductSummaries(Pageable pageable);

    // 재고 수정 시 행 잠금
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
    SELECT i
    FROM Inventory i
    WHERE i.product.id = :productId
    """)
    Optional<Inventory> findByProductIdForUpdate(
            @Param("productId") Long productId
    );
}