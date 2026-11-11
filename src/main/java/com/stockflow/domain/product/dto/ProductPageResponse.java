package com.stockflow.domain.product.dto;

import org.springframework.data.domain.Page;

import java.util.List;

// 페이징 응답 DTO
public record ProductPageResponse(
        List<ProductResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static ProductPageResponse from(Page<ProductResponse> result) {
        return new ProductPageResponse(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }
}