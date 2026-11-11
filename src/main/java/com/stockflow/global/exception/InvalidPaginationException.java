package com.stockflow.global.exception;

// 잘못된 페이징 요청 처리
// 일반적인 IllegalArgumentException을 전부 400으로 처리하지 않고,
// 페이징 오류 전용 예외를 만들어 구분
public class InvalidPaginationException extends RuntimeException {

    public InvalidPaginationException() {
        super("페이지는 0 이상, 조회 크기는 1~100이어야 합니다.");
    }
}