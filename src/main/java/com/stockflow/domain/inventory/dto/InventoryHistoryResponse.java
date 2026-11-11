package com.stockflow.domain.inventory.dto;

import com.stockflow.domain.inventory.InventoryChangeType;
import java.time.LocalDateTime;

public record InventoryHistoryResponse(
        Long historyId,
        InventoryChangeType changeType,
        int quantityChange,
        int quantityAfter,
        LocalDateTime createdAt
) {
}