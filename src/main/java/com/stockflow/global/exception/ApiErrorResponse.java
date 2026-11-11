package com.stockflow.global.exception;

public record ApiErrorResponse(
        String code,
        String message
) {
}