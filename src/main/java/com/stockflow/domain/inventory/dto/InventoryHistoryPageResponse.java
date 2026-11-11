package com.stockflow.domain.inventory.dto;

import org.springframework.data.domain.Page;
import java.util.List;

public record InventoryHistoryPageResponse(
        List<InventoryHistoryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static InventoryHistoryPageResponse from(
            Page<InventoryHistoryResponse> result
    ) {
        return new InventoryHistoryPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }
}