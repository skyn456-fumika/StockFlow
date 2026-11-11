package com.stockflow.domain.order;

import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.order.dto.OrderCreateRequest;
import com.stockflow.domain.order.dto.OrderCreateResponse;

import com.stockflow.domain.order.dto.OrderDetailResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // 주문 생성 REST API
    @PostMapping
    public ResponseEntity<OrderCreateResponse> createOrder(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request
    ) {

        if (idempotencyKey.isBlank()
                || idempotencyKey.length() > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "올바른 Idempotency-Key가 필요합니다."
            );
        }

        List<OrderCreateItem> items = request.items().stream()
                .map(item -> new OrderCreateItem(
                        item.productId(),
                        item.quantity()
                ))
                .toList();

        Long orderId = orderService.createOrder(
                items,
                idempotencyKey
        );

        return ResponseEntity
                .created(URI.create("/api/orders/" + orderId))
                .body(new OrderCreateResponse(orderId));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderDetailResponse> getOrder(
            @PathVariable Long orderId
    ) {
        return ResponseEntity.ok(
                orderService.getOrder(orderId)
        );
    }

    // 취소 API
    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<Void> cancelOrder(
            @PathVariable Long orderId
    ) {
        orderService.cancelOrder(orderId);

        // 취소 성공 시 응답 본문 없이 204 No Content를 반환하도록
        return ResponseEntity.noContent().build();
    }
}