package com.stockflow.domain.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 재고 변경 요청 DTO
public record InventoryChangeRequest(

        @NotNull(message = "변경 수량은 필수입니다.")
        @Min(value = 1, message = "변경 수량은 1 이상이어야 합니다.")
        Integer amount

) {
}