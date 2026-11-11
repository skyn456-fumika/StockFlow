package com.stockflow.domain.inventory;

import com.stockflow.domain.inventory.dto.InventoryChangeRequest;
import com.stockflow.domain.inventory.dto.InventoryResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.stockflow.domain.inventory.dto.InventoryHistoryPageResponse;

@RestController
@RequestMapping("/api/inventories")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    // 재고 입고
    @PostMapping("/{productId}/increase")
    public ResponseEntity<InventoryResponse> increase(
            @PathVariable Long productId,
            @Valid @RequestBody InventoryChangeRequest request
    ) {
        int quantity = inventoryService.increase(
                productId,
                request.amount()
        );

        return ResponseEntity.ok(
                new InventoryResponse(productId, quantity)
        );
    }

    // 재고 출고
    @PostMapping("/{productId}/decrease")
    public ResponseEntity<InventoryResponse> decrease(
            @PathVariable Long productId,
            @Valid @RequestBody InventoryChangeRequest request
    ) {
        int quantity = inventoryService.decrease(
                productId,
                request.amount()
        );

        return ResponseEntity.ok(
                new InventoryResponse(productId, quantity)
        );
    }

    // 재고 변경 이력 조회 API
    @GetMapping("/{productId}/histories")
    public ResponseEntity<InventoryHistoryPageResponse> getHistories(
            @PathVariable Long productId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return ResponseEntity.ok(
                inventoryService.getHistories(productId, page, size)
        );
    }
}