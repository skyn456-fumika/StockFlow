package com.stockflow.domain.order.outbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.config.RabbitRoutingVerifier;
import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
@org.junit.jupiter.api.Tag("integration")
class OrderOutboxPublishFailureTest {

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

    @MockitoBean
    RabbitRoutingVerifier routingVerifier;

    private Long createPendingOutbox() {

        Long productId = productService.registerProduct(
                "발행 실패 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 2))
        );

        return outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0)
                .getId();
    }

    private void assertPending(Long outboxId) {

        OrderOutbox found = outboxRepository
                .findById(outboxId)
                .orElseThrow();

        assertThat(found.getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        assertThat(found.getPublishedAt())
                .isNull();
    }

    @Test
    void RabbitMQ_발행에_실패하면_Outbox는_PENDING으로_남는다() {

        Long outboxId = createPendingOutbox();

        doThrow(
                new AmqpIOException(
                        new IOException("RabbitMQ 발행 실패")
                )
        ).when(routingVerifier).sendAndVerify(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.OUTBOX_ROUTING_KEY),
                any(Message.class)
        );

        assertThatThrownBy(() -> publisher.publishOne(outboxId))
                .isInstanceOf(AmqpIOException.class);

        assertPending(outboxId);
    }

    @Test
    void RabbitMQ_라우팅에_실패하면_Outbox는_PENDING으로_남는다() {

        Long outboxId = createPendingOutbox();

        doThrow(
                new IllegalStateException("RabbitMQ 라우팅 실패")
        ).when(routingVerifier).sendAndVerify(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.OUTBOX_ROUTING_KEY),
                any(Message.class)
        );

        assertThatThrownBy(() -> publisher.publishOne(outboxId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("라우팅 실패");

        assertPending(outboxId);

        verify(routingVerifier).sendAndVerify(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.OUTBOX_ROUTING_KEY),
                any(Message.class)
        );
    }
}