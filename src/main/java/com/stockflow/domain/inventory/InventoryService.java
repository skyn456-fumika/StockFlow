package com.stockflow.domain.inventory;

import com.stockflow.global.exception.InvalidPaginationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.stockflow.domain.inventory.exception.InventoryNotFoundException;

import com.stockflow.domain.inventory.InventoryChangeType;
import com.stockflow.domain.inventory.InventoryHistory;

import com.stockflow.domain.inventory.dto.InventoryHistoryPageResponse;
import org.springframework.data.domain.PageRequest;

@Service
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryHistoryRepository inventoryHistoryRepository;

    public InventoryService(
            InventoryRepository inventoryRepository,
            InventoryHistoryRepository inventoryHistoryRepository
    ) {
        this.inventoryRepository = inventoryRepository;
        this.inventoryHistoryRepository = inventoryHistoryRepository;
    }

    // 입고
    @Transactional
    public int increase(Long productId, int amount) {

        Inventory inventory = inventoryRepository
                .findByProductIdForUpdate(productId)
                .orElseThrow(() -> new InventoryNotFoundException(productId));

        inventory.increase(amount);

        // 입고는 양수
        inventoryHistoryRepository.save(
                new InventoryHistory(
                        inventory,
                        InventoryChangeType.INBOUND,
                        amount,
                        inventory.getQuantity()
                )
        );

        return inventory.getQuantity();
    }

    // 트랜잭션 안에서 재고를 조회하고 수정하므로,
    // JPA의 변경 감지(Dirty Checking)에 의해
    // 트랜잭션 커밋 시 재고 수량이 DB에 반영
    // 출고
    @Transactional
    public int decrease(Long productId, int amount) {

        Inventory inventory = inventoryRepository
                .findByProductIdForUpdate(productId)
                .orElseThrow(() -> new InventoryNotFoundException(productId));

        inventory.decrease(amount);

        // 출고는 음수로 기록하기로 했으므로 DB CHECK 제약조건과도 일치
        inventoryHistoryRepository.save(
                new InventoryHistory(
                        inventory,
                        InventoryChangeType.OUTBOUND,
                        -amount,
                        inventory.getQuantity()
                )
        );

        return inventory.getQuantity();
    }

    @Transactional(readOnly = true)
    public InventoryHistoryPageResponse getHistories(
            Long productId,
            int page,
            int size
    ) {
        // 1. 페이지 번호와 크기 검증
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidPaginationException();
        }

        // 2. 상품 ID로 Inventory 조회
        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow(() ->
                        new InventoryNotFoundException(productId)
                );

        // 4. 페이징 응답 생성
        return InventoryHistoryPageResponse.from(
                // 3. 재고 ID로 변경 이력 조회
                inventoryHistoryRepository.findHistoryPage(
                        inventory.getId(),
                        PageRequest.of(page, size)
                )
        );
    }
}