package com.stockflow.domain.product.dto;

import com.stockflow.domain.product.Product;
import com.stockflow.domain.product.ProductStatus;

import java.math.BigDecimal;

public record ProductInfoResponse(
        Long productId,
        String name,
        BigDecimal price,
        ProductStatus status
) {
    public static ProductInfoResponse from(Product product) {
        return new ProductInfoResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getStatus()
        );
    }
}