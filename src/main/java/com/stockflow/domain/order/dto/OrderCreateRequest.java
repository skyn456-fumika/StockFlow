package com.stockflow.domain.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record OrderCreateRequest(

        // 주문 요청 안에 items라는 목록이 있고, 
        // 그 안에 상품 ID와 수량이 들어가기 때문에 
        // 중첩된 요청 데이터까지 검증해야 함
        @NotEmpty(message = "주문 상품은 1개 이상이어야 합니다.")
        List<@NotNull @Valid Item> items

) {
    public record Item(

            @NotNull(message = "상품 ID는 필수입니다.")
            Long productId,

            @NotNull(message = "주문 수량은 필수입니다.")
            @Positive(message = "주문 수량은 1 이상이어야 합니다.")
            Integer quantity

    ) {
    }
}