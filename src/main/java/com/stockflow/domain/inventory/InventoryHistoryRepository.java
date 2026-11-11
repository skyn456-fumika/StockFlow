package com.stockflow.domain.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import com.stockflow.domain.inventory.dto.InventoryHistoryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryHistoryRepository
        extends JpaRepository<InventoryHistory, Long> {

    // 특정 재고의 변경 이력을 최신순으로 조회할 수 있게 준비
    List<InventoryHistory> findByInventoryIdOrderByIdDesc(
            Long inventoryId
    );

    @Query(
            value = """
        SELECT new com.stockflow.domain.inventory.dto.InventoryHistoryResponse(
            h.id,
            h.changeType,
            h.quantityChange,
            h.quantityAfter,
            h.createdAt
        )
        FROM InventoryHistory h
        WHERE h.inventory.id = :inventoryId
        ORDER BY h.id DESC
        """,
            countQuery = """
        SELECT COUNT(h)
        FROM InventoryHistory h
        WHERE h.inventory.id = :inventoryId
        """
    )
    Page<InventoryHistoryResponse> findHistoryPage(
            @Param("inventoryId") Long inventoryId,
            Pageable pageable
    );
}