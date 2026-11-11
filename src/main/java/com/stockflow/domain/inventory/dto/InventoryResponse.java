package com.stockflow.domain.inventory.dto;

// 재고 변경 응답 DTO
public record InventoryResponse(
        Long productId,
        int quantity
) {
}