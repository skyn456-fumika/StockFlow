package com.stockflow.domain.product.event;

// 상품 정보 변경 이벤트
// 상품 정보 변경이 발생했다는 사실을 전달하는 이벤트
public record ProductInfoChangedEvent(Long productId) {
}