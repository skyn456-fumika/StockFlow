package com.stockflow.domain.product;

import com.stockflow.domain.inventory.Inventory;
import com.stockflow.domain.inventory.InventoryRepository;
import com.stockflow.support.SharedContainers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Tag("integration")
class ProductServiceTest extends SharedContainers {

    @Autowired
    ProductService productService;

    @Autowired
    ProductRepository productRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Test
    void 상품_등록시_초기_재고도_함께_생성된다() {
        Long productId = productService.registerProduct(
                "키보드",
                new BigDecimal("75000"),
                30
        );

        Product product = productRepository.findById(productId)
                .orElseThrow();

        Inventory inventory = inventoryRepository
                .findByProductId(productId)
                .orElseThrow();

        assertThat(product.getName()).isEqualTo("키보드");
        assertThat(product.getPrice())
                .isEqualByComparingTo("75000");
        assertThat(inventory.getQuantity()).isEqualTo(30);
        assertThat(inventory.getProduct().getId())
                .isEqualTo(productId);
    }

    @Test
    void 잘못된_초기_재고로_상품을_등록할_수_없다() {
        long beforeCount = productRepository.count();

        assertThatThrownBy(() ->
                productService.registerProduct(
                        "마우스",
                        new BigDecimal("30000"),
                        -5
                )
        ).isInstanceOf(IllegalArgumentException.class);

        assertThat(productRepository.count())
                .isEqualTo(beforeCount);
    }
}