package com.stockflow.domain.inventory;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "inventory_histories")
public class InventoryHistory {

    // 이력 ID
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 재고 ID (FK)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_id", nullable = false)
    private Inventory inventory;

    // 변경 유형
    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private InventoryChangeType changeType;

    // 증감 수량
    @Column(name = "quantity_change", nullable = false)
    private int quantityChange;

    // 변경 후 재고
    @Column(name = "quantity_after", nullable = false)
    private int quantityAfter;

    // 변경 시각
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected InventoryHistory() {
    }

    public InventoryHistory(
            Inventory inventory,
            InventoryChangeType changeType,
            int quantityChange,
            int quantityAfter
    ) {
        if (inventory == null || changeType == null) {
            throw new IllegalArgumentException(
                    "재고와 변경 유형은 필수입니다."
            );
        }

        if (quantityAfter < 0) {
            throw new IllegalArgumentException(
                    "변경 후 재고는 0 이상이어야 합니다."
            );
        }

        this.inventory = inventory;
        this.changeType = changeType;
        this.quantityChange = quantityChange;
        this.quantityAfter = quantityAfter;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public InventoryChangeType getChangeType() {
        return changeType;
    }

    public int getQuantityChange() {
        return quantityChange;
    }

    public int getQuantityAfter() {
        return quantityAfter;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Inventory getInventory() {
        return inventory;
    }
}