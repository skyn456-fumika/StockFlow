package com.stockflow.domain.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ProductUpdateRequest(

        @NotBlank(message = "상품명은 필수입니다.")
        @Size(max = 100, message = "상품명은 100자 이하여야 합니다.")
        String name,

        @NotNull(message = "가격은 필수입니다.")
        @DecimalMin(value = "0.0", message = "가격은 0 이상이어야 합니다.")
        @Digits(integer = 10, fraction = 2)
        BigDecimal price

) {
}