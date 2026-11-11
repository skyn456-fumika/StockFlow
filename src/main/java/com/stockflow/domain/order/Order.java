package com.stockflow.domain.order;

import com.stockflow.domain.order.exception.OrderCancellationException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 주문 전체 상태와 총액 관리
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(
            name = "total_amount",
            nullable = false,
            precision = 15,
            scale = 2
    )
    private BigDecimal totalAmount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // OrderRepository.save()를 호출하면 연결된 OrderItem도 함께 저장
    @OneToMany(
            mappedBy = "order",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private List<OrderItem> items = new ArrayList<>();

    @Column(
            name = "idempotency_key",
            length = 100,
            unique = true
    )
    private String idempotencyKey;

    public Order() {
        this.status = OrderStatus.CREATED;
        this.totalAmount = BigDecimal.ZERO;
        this.createdAt = LocalDateTime.now();
    }

    public Order(String idempotencyKey) {
        this();

        if (idempotencyKey == null
                || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException(
                    "멱등성 키는 필수입니다."
            );
        }

        if (idempotencyKey.length() > 100) {
            throw new IllegalArgumentException(
                    "멱등성 키는 100자를 초과할 수 없습니다."
            );
        }

        this.idempotencyKey = idempotencyKey;
    }

    public void addItem(OrderItem item) {
        if (item == null) {
            throw new IllegalArgumentException(
                    "주문 상품은 필수입니다."
            );
        }

        item.assignOrder(this);
        items.add(item);

        this.totalAmount = this.totalAmount.add(
                item.getSubtotal()
        );
    }

    public Long getId() {
        return id;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    // 취소 메서드
    public void cancel() {
        if (this.status != OrderStatus.CREATED) {
            throw new OrderCancellationException();
        }

        this.status = OrderStatus.CANCELLED;
    }
}