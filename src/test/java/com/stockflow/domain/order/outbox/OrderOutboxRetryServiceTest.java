package com.stockflow.domain.order.outbox;

import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
        "stockflow.outbox.scheduling-enabled=false",
        "stockflow.outbox.consumer.enabled=false"
})
@Testcontainers
class OrderOutboxRetryServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine")
                    .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq =
            new RabbitMQContainer("rabbitmq:4.3-management-alpine");

    @Autowired
    ProductService productService;

    @Autowired
    OrderService orderService;

    @Autowired
    OrderOutboxRepository outboxRepository;

    @Autowired
    OrderOutboxRetryService retryService;

    private Long createPendingOutbox() {

        Long productId = productService.registerProduct(
                "Outbox 재시도 기록 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        return outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0)
                .getId();
    }

    @Test
    void 발행_실패를_기록하면_재시도_정보가_DB에_저장된다() {

        Long outboxId = createPendingOutbox();

        LocalDateTime before = LocalDateTime.now();

        retryService.recordFailure(outboxId);

        LocalDateTime after = LocalDateTime.now();

        OrderOutbox saved = outboxRepository
                .findById(outboxId)
                .orElseThrow();

        assertThat(saved.getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        assertThat(saved.getRetryCount())
                .isEqualTo(1);

        assertThat(saved.getNextRetryAt())
                .isBetween(
                        before.plusSeconds(5),
                        after.plusSeconds(5)
                );
    }

    @Test
    void 반복_실패하면_DB의_실패_횟수가_증가한다() {

        Long outboxId = createPendingOutbox();

        retryService.recordFailure(outboxId);
        retryService.recordFailure(outboxId);

        OrderOutbox saved = outboxRepository
                .findById(outboxId)
                .orElseThrow();

        assertThat(saved.getRetryCount())
                .isEqualTo(2);

        assertThat(saved.getNextRetryAt())
                .isAfter(LocalDateTime.now().plusSeconds(5));
    }

    @Test
    void 이미_발행된_이벤트는_실패_횟수를_변경하지_않는다() {

        Long outboxId = createPendingOutbox();

        OrderOutbox outbox = outboxRepository
                .findById(outboxId)
                .orElseThrow();

        // 이 테스트에서는 RabbitMQ 발행 없이 상태만 변경
        outbox.markPublished();
        outboxRepository.saveAndFlush(outbox);

        retryService.recordFailure(outboxId);

        OrderOutbox saved = outboxRepository
                .findById(outboxId)
                .orElseThrow();

        assertThat(saved.getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);

        assertThat(saved.getRetryCount())
                .isZero();

        assertThat(saved.getNextRetryAt())
                .isNull();
    }
}