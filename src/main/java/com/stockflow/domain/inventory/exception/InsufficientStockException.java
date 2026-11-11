package com.stockflow.domain.inventory.exception;

public class InsufficientStockException extends IllegalStateException {

    public InsufficientStockException() {
        super("재고가 부족합니다.");
    }
}