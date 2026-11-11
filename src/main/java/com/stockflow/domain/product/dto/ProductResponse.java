package com.stockflow.domain.product.dto;

import com.stockflow.domain.product.ProductStatus;
import java.math.BigDecimal;

public record ProductResponse(
        Long productId,
        String name,
        BigDecimal price,
        ProductStatus status,
        int quantity
) {
}