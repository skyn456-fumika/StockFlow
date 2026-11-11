package com.stockflow.domain.order.exception;

// 판매 중지 상품 주문
public class ProductNotAvailableException extends RuntimeException {

    public ProductNotAvailableException(Long productId) {
        super("판매 중인 상품이 아닙니다. productId=" + productId);
    }
}