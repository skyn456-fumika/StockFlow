package com.stockflow.domain.inventory;

import com.stockflow.domain.product.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Testcontainers
@Transactional
class InventoryServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ProductService productService;

    @Autowired
    InventoryService inventoryService;

    @Autowired
    InventoryRepository inventoryRepository;

    @Test
    void 재고_입고_후_수량이_증가한다() {
        Long productId = productService.registerProduct(
                "키보드", new BigDecimal("50000"), 10
        );

        int quantity = inventoryService.increase(productId, 5);

        assertThat(quantity).isEqualTo(15);
        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(15);
    }

    @Test
    void 재고_출고_후_수량이_감소한다() {
        Long productId = productService.registerProduct(
                "마우스", new BigDecimal("30000"), 10
        );

        int quantity = inventoryService.decrease(productId, 3);

        assertThat(quantity).isEqualTo(7);
        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(7);
    }

    @Test
    void 재고가_부족하면_출고되지_않는다() {
        Long productId = productService.registerProduct(
                "모니터", new BigDecimal("200000"), 2
        );

        assertThatThrownBy(() ->
                inventoryService.decrease(productId, 3)
        ).isInstanceOf(IllegalStateException.class)
                .hasMessage("재고가 부족합니다.");

        assertThat(inventoryRepository.findByProductId(productId)
                .orElseThrow().getQuantity()).isEqualTo(2);
    }
}