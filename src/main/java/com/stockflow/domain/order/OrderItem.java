package com.stockflow.domain.order;

import com.stockflow.domain.product.Product;
import jakarta.persistence.*;

import java.math.BigDecimal;

// 주문한 상품, 수량, 주문 당시 가격
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(
            name = "unit_price",
            nullable = false,
            precision = 12,
            scale = 2
    )
    private BigDecimal unitPrice;

    protected OrderItem() {
    }

    public OrderItem(Product product, int quantity) {
        if (product == null) {
            throw new IllegalArgumentException(
                    "상품은 필수입니다."
            );
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "주문 수량은 1 이상이어야 합니다."
            );
        }

        this.product = product;
        this.quantity = quantity;
        this.unitPrice = product.getPrice();
    }

    void assignOrder(Order order) {
        this.order = order;
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

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getSubtotal() {
        return unitPrice.multiply(
                BigDecimal.valueOf(quantity)
        );
    }
}