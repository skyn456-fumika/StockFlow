package com.stockflow.domain.inventory;

import com.stockflow.domain.product.Product;
import com.stockflow.domain.product.ProductRepository;
import com.stockflow.support.SharedContainers;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Tag("integration")
class InventoryRepositoryTest extends SharedContainers {

    @Autowired
    ProductRepository productRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    @Test
    void 상품에_연결된_재고를_저장하고_조회한다() {
        // given
        Product product = productRepository.save(
                new Product("테스트 상품", new BigDecimal("10000"))
        );

        Inventory inventory = inventoryRepository.save(
                new Inventory(product, 50)
        );

        // when
        Inventory found = inventoryRepository
                .findByProductId(product.getId())
                .orElseThrow();

        // then
        assertThat(found.getId()).isEqualTo(inventory.getId());
        assertThat(found.getProduct().getId()).isEqualTo(product.getId());
        assertThat(found.getQuantity()).isEqualTo(50);
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 음수_재고는_생성할_수_없다() {
        Product product = new Product(
                "테스트 상품",
                new BigDecimal("10000")
        );

        assertThatThrownBy(() -> new Inventory(product, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재고 수량은 0 이상이어야 합니다.");
    }

    @Test
    void 재고_저장_시_버전이_초기화된다() {
        Product product = productRepository.save(
                new Product("버전 테스트 상품", new BigDecimal("20000"))
        );

        Inventory inventory = inventoryRepository.saveAndFlush(
                new Inventory(product, 10)
        );

        assertThat(inventory.getVersion()).isEqualTo(0L);
    }
}