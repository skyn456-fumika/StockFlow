package com.stockflow.domain.order.dto;

// 주문할 상품 ID와 수량을 전달하는 객체
public record OrderCreateItem(
        Long productId,
        int quantity
) {
}