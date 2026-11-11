package com.stockflow.domain.order.exception;

// 동일 상품 중복 요청
public class DuplicateOrderProductException extends RuntimeException {

    public DuplicateOrderProductException(Long productId) {
        super("중복된 주문 상품입니다. productId=" + productId);
    }
}