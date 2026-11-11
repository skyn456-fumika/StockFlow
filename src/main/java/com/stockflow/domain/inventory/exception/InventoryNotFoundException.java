package com.stockflow.domain.inventory.exception;

public class InventoryNotFoundException extends RuntimeException {

    public InventoryNotFoundException(Long productId) {
        super("재고 정보를 찾을 수 없습니다. productId=" + productId);
    }
}