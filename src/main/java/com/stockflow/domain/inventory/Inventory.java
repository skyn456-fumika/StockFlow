package com.stockflow.domain.inventory;

import com.stockflow.domain.product.Product;
import jakarta.persistence.*;

import java.time.LocalDateTime;

import com.stockflow.domain.inventory.exception.InsufficientStockException;

@Entity
@Table(name = "inventories")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 상품과 재고를 1:1 관계로 연결
    // 연관된 Product를 필요할 때 조회
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    // product_id 외래키 지정
    @JoinColumn(
            name = "product_id",
            nullable = false,
            unique = true
    )
    private Product product;

    @Column(nullable = false)
    private int quantity;

    // 낙관적 락 버전 관리
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(
            name = "updated_at",
            nullable = false
    )
    private LocalDateTime updatedAt;

    protected Inventory() {
    }

    public Inventory(Product product, int quantity) {
        if (product == null) {
            throw new IllegalArgumentException("상품은 필수입니다.");
        }

        if (quantity < 0) {
            throw new IllegalArgumentException(
                    "재고 수량은 0 이상이어야 합니다."
            );
        }

        this.product = product;
        this.quantity = quantity;
    }

    // 최초 저장 전 시간 설정
    @PrePersist
    // 수정 전 시간 갱신
    @PreUpdate
    private void updateTimestamp() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void increase(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "입고 수량은 1 이상이어야 합니다."
            );
        }

        // 정수 범위를 초과하는 덧셈을 감지해 ArithmeticException을 발생
        // 일반적인 int 덧셈에서 오버플로로 재고가 음수가 되는 문제를 방지
        this.quantity = Math.addExact(this.quantity, amount);
        this.updatedAt = LocalDateTime.now();
    }

    public void decrease(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "출고 수량은 1 이상이어야 합니다."
            );
        }

        if (this.quantity < amount) {
            throw new InsufficientStockException();
        }

        this.quantity -= amount;
        this.updatedAt = LocalDateTime.now();
    }
}