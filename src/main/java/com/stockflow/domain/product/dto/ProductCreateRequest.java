package com.stockflow.domain.product.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

// record를 사용하면 생성자와 getter 역할을 하는 접근자 등을 자동으로 제공
public record ProductCreateRequest(

        @NotBlank(message = "상품명은 필수입니다.")
        @Size(max = 100, message = "상품명은 100자 이하여야 합니다.")
        String name,

        @NotNull(message = "가격은 필수입니다.")
        @DecimalMin(value = "0.0", message = "가격은 0 이상이어야 합니다.")
        @Digits(integer = 10, fraction = 2)
        BigDecimal price,

        @NotNull(message = "초기 재고는 필수입니다.")
        @Min(value = 0, message = "초기 재고는 0 이상이어야 합니다.")
        Integer initialQuantity

) {}