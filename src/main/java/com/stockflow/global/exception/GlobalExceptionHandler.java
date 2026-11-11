package com.stockflow.global.exception;

import com.stockflow.domain.inventory.exception.InventoryNotFoundException;
import com.stockflow.domain.inventory.exception.InsufficientStockException;
import com.stockflow.domain.order.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InventoryNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleInventoryNotFound(
            InventoryNotFoundException e
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        "INVENTORY_NOT_FOUND",
                        e.getMessage()
                ));
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiErrorResponse> handleInsufficientStock(
            InsufficientStockException e
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "INSUFFICIENT_STOCK",
                        e.getMessage()
                ));
    }

    @ExceptionHandler(InvalidPaginationException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidPagination(
            InvalidPaginationException e
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(
                        "INVALID_PAGINATION",
                        e.getMessage()
                ));
    }

    @ExceptionHandler(OrderProductNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleOrderProductNotFound(
            OrderProductNotFoundException e
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        "ORDER_PRODUCT_NOT_FOUND",
                        e.getMessage()
                ));
    }

    @ExceptionHandler(ProductNotAvailableException.class)
    public ResponseEntity<ApiErrorResponse> handleProductNotAvailable(
            ProductNotAvailableException e
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "PRODUCT_NOT_AVAILABLE",
                        e.getMessage()
                ));
    }

    @ExceptionHandler(DuplicateOrderProductException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateOrderProduct(
            DuplicateOrderProductException e
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(
                        "DUPLICATE_ORDER_PRODUCT",
                        e.getMessage()
                ));
    }

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleOrderNotFound(
            OrderNotFoundException e
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiErrorResponse(
                        "ORDER_NOT_FOUND",
                        e.getMessage()
                ));
    }

    @ExceptionHandler(OrderCancellationException.class)
    public ResponseEntity<ApiErrorResponse> handleOrderCancellation(
            OrderCancellationException e
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiErrorResponse(
                        "ORDER_CANNOT_CANCEL",
                        e.getMessage()
                ));
    }
}