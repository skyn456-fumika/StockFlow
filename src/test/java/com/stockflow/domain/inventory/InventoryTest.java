package com.stockflow.domain.inventory;

import com.stockflow.domain.product.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

class InventoryTest {

    private Inventory createInventory(int quantity) {
        Product product = new Product(
                "테스트 상품",
                new BigDecimal("10000")
        );

        return new Inventory(product, quantity);
    }

    @Test
    void 재고를_증가시킨다() {
        Inventory inventory = createInventory(10);

        inventory.increase(5);

        assertThat(inventory.getQuantity()).isEqualTo(15);
    }

    @Test
    void 재고를_차감한다() {
        Inventory inventory = createInventory(10);

        inventory.decrease(3);

        assertThat(inventory.getQuantity()).isEqualTo(7);
    }

    @Test
    void 재고가_부족하면_예외가_발생한다() {
        Inventory inventory = createInventory(2);

        assertThatThrownBy(() -> inventory.decrease(3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("재고가 부족합니다.");

        assertThat(inventory.getQuantity()).isEqualTo(2);
    }

    @Test
    void 입출고_수량은_양수여야_한다() {
        Inventory inventory = createInventory(10);

        assertThatThrownBy(() -> inventory.increase(0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> inventory.decrease(-1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inventory.getQuantity()).isEqualTo(10);
    }

    @Test
    void 재고_증가시_정수_오버플로를_방지한다() {
        Inventory inventory = createInventory(Integer.MAX_VALUE);

        assertThatThrownBy(() -> inventory.increase(1))
                .isInstanceOf(ArithmeticException.class);

        assertThat(inventory.getQuantity())
                .isEqualTo(Integer.MAX_VALUE);
    }
}