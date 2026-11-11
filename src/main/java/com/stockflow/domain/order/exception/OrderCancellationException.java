package com.stockflow.domain.order.exception;

// 주문 취소 전용 예외
public class OrderCancellationException extends IllegalStateException {

    public OrderCancellationException() {
        super("취소할 수 없는 주문 상태입니다.");
    }
}