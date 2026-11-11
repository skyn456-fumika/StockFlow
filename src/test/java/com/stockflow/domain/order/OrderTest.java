package com.stockflow.domain.order;

import com.stockflow.domain.product.Product;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

// 주문 금액 계산 테스트
class OrderTest {

    private Product createProduct(String name, String price) {
        return new Product(name, new BigDecimal(price));
    }

    @Test
    void 주문_상품을_추가하면_총금액이_계산된다() {
        Product keyboard = createProduct("키보드", "50000");
        Product mouse = createProduct("마우스", "30000");

        Order order = new Order();

        order.addItem(new OrderItem(keyboard, 2));
        order.addItem(new OrderItem(mouse, 3));

        assertThat(order.getItems()).hasSize(2);

        // 50000 * 2 + 30000 * 3 = 190000
        assertThat(order.getTotalAmount())
                .isEqualByComparingTo("190000");

        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void 주문_당시_상품_단가로_소계가_계산된다() {
        Product product = createProduct("모니터", "250000");

        OrderItem item = new OrderItem(product, 2);

        assertThat(item.getUnitPrice())
                .isEqualByComparingTo("250000");

        assertThat(item.getSubtotal())
                .isEqualByComparingTo("500000");
    }

    @Test
    void 주문_수량이_0이하면_예외가_발생한다() {
        Product product = createProduct("키보드", "50000");

        assertThatThrownBy(() -> new OrderItem(product, 0))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new OrderItem(product, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 새_주문의_초기_금액은_0이다() {
        Order order = new Order();

        assertThat(order.getTotalAmount())
                .isEqualByComparingTo("0");

        assertThat(order.getItems()).isEmpty();
        assertThat(order.getCreatedAt()).isNotNull();
    }
}