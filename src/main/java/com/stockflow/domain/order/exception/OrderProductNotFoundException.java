package com.stockflow.domain.order.exception;

// 존재하지 않는 상품 주문
public class OrderProductNotFoundException extends RuntimeException {

    public OrderProductNotFoundException(Long productId) {
        super("상품을 찾을 수 없습니다. productId=" + productId);
    }
}