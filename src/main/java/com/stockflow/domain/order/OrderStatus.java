package com.stockflow.domain.order;

// 주문 상태 관리
public enum OrderStatus {
    CREATED,    // 주문 생성 완료, 재고 차감 완료
    CANCELLED,  // 주문 취소, 재고 복구 완료
    COMPLETED   // 주문 처리 완료
}