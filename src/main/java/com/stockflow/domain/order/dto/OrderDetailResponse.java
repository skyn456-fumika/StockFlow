package com.stockflow.domain.order.dto;

import com.stockflow.domain.order.Order;
import com.stockflow.domain.order.OrderItem;
import com.stockflow.domain.order.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// 주문 상세 응답 DTO
public record OrderDetailResponse(
        Long orderId,
        OrderStatus status,
        BigDecimal totalAmount,
        LocalDateTime createdAt,
        List<Item> items
) {
    public record Item(
            Long productId,
            String productName,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal subtotal
    ) {
        public static Item from(OrderItem orderItem) {
            return new Item(
                    orderItem.getProduct().getId(),
                    orderItem.getProduct().getName(),
                    orderItem.getQuantity(),
                    orderItem.getUnitPrice(),
                    orderItem.getSubtotal()
            );
        }
    }

    public static OrderDetailResponse from(Order order) {
        return new OrderDetailResponse(
                order.getId(),
                order.getStatus(),
                order.getTotalAmount(),
                order.getCreatedAt(),
                order.getItems().stream()
                        .map(Item::from)
                        .toList()
        );
    }
}