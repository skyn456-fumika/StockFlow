package com.stockflow.domain.order.outbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@org.junit.jupiter.api.Tag("integration")
class OrderOutboxPublisherTest {

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
    OrderOutboxPublisher publisher;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Test
    // PENDING → RabbitMQ 발행 → PUBLISHED 흐름을 검증
    void PENDING_이벤트를_발행하면_PUBLISHED로_변경된다() {

        Long productId = productService.registerProduct(
                "Outbox 발행 테스트",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 2))
        );

        OrderOutbox outbox = outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0);

        assertThat(outbox.getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        boolean published = publisher.publishOne(outbox.getId());

        assertThat(published).isTrue();

        OrderOutbox updated = outboxRepository
                .findById(outbox.getId())
                .orElseThrow();

        assertThat(updated.getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);

        assertThat(updated.getPublishedAt()).isNotNull();

        Message received = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_QUEUE,
                5000
        );

        assertThat(received).isNotNull();

        String payload = new String(
                received.getBody(),
                StandardCharsets.UTF_8
        );

        assertThat(payload).isEqualTo(outbox.getPayload());

        assertThat(received.getMessageProperties().getMessageId())
                .isEqualTo(outbox.getEventId().toString());
    }

    @Test
    // 이미 PUBLISHED 상태인 이벤트를 다시 발행하지 않는지 확인
    void 이미_발행된_이벤트는_다시_발행하지_않는다() {

        Long productId = productService.registerProduct(
                "중복 발행 테스트",
                new BigDecimal("20000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        OrderOutbox outbox = outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0);

        boolean first = publisher.publishOne(outbox.getId());

        assertThat(first).isTrue();

        // 첫 메시지를 큐에서 제거
        Message firstMessage = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_QUEUE,
                5000
        );

        assertThat(firstMessage).isNotNull();

        // 이미 PUBLISHED이므로 재발행하지 않음
        boolean second = publisher.publishOne(outbox.getId());

        assertThat(second).isFalse();

        Message duplicate = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_QUEUE,
                500
        );

        assertThat(duplicate).isNull();
    }
}