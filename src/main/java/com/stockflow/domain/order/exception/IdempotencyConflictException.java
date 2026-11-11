package com.stockflow.domain.order.exception;

// 주문 내용 불일치 예외
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("동일한 멱등성 키로 다른 주문을 요청할 수 없습니다.");
    }
}