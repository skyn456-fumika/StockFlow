package com.stockflow.domain.order;

import com.stockflow.domain.product.Product;
import com.stockflow.domain.product.ProductRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
// 각 테스트가 종료되면 데이터가 롤백되므로 테스트 간 데이터 간섭을 줄임
@Transactional
class OrderRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    ProductRepository productRepository;

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    OrderItemRepository orderItemRepository;

    @Autowired
    EntityManager entityManager;

    @Test
    void 주문과_주문상품을_함께_저장한다() {
        // given
        Product keyboard = productRepository.save(
                new Product("키보드", new BigDecimal("50000"))
        );

        Product mouse = productRepository.save(
                new Product("마우스", new BigDecimal("30000"))
        );

        Order order = new Order();

        order.addItem(new OrderItem(keyboard, 2));
        order.addItem(new OrderItem(mouse, 3));

        // when
        Order saved = orderRepository.saveAndFlush(order);
        Long orderId = saved.getId();

        // 영속성 컨텍스트 초기화
        // 1차 캐시에 남아 있는 엔티티가 아니라
        // 실제 DB에 저장된 데이터를 다시 조회하도록
        entityManager.clear();

        // then
        Order found = orderRepository.findById(orderId)
                .orElseThrow();

        List<OrderItem> items =
                orderItemRepository.findByOrderId(orderId);

        assertThat(found.getStatus())
                .isEqualTo(OrderStatus.CREATED);

        assertThat(found.getTotalAmount())
                .isEqualByComparingTo("190000");

        assertThat(items).hasSize(2);

        assertThat(items)
                .extracting(OrderItem::getQuantity)
                .containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void 주문상품의_단가와_수량이_DB에_저장된다() {
        Product product = productRepository.save(
                new Product("모니터", new BigDecimal("250000"))
        );

        Order order = new Order();
        order.addItem(new OrderItem(product, 2));

        Long orderId = orderRepository.saveAndFlush(order).getId();

        entityManager.clear();

        OrderItem found = orderItemRepository
                .findByOrderId(orderId)
                .get(0);

        assertThat(found.getUnitPrice())
                .isEqualByComparingTo("250000");

        assertThat(found.getQuantity()).isEqualTo(2);

        assertThat(found.getSubtotal())
                .isEqualByComparingTo("500000");
    }
}