package com.stockflow.domain.product;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProductStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Product() {
    }

    public Product(String name, BigDecimal price) {
        this.name = name;
        this.price = price;
        this.status = ProductStatus.ACTIVE;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public ProductStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    // JPA 변경 감지를 이용할 예정이므로 별도로 save() x
    public void updateInfo(String name, BigDecimal price) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException("올바른 상품명이 필요합니다.");
        }

        if (price == null || price.signum() < 0
                || price.scale() > 2
                || price.precision() - price.scale() > 10) {
            throw new IllegalArgumentException("올바른 상품 가격이 필요합니다.");
        }

        this.name = name;
        this.price = price;
    }
}