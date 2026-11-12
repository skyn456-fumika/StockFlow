package com.stockflow.domain.order.outbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.config.RabbitRoutingVerifier;
import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "stockflow.outbox.scheduling-enabled=false",
        "stockflow.outbox.consumer.enabled=false"
})
@Testcontainers
// 실제 DB 재시도 기록 통합 테스트
class OrderOutboxRetryIntegrationTest {

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
    OrderOutboxScheduler scheduler;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @MockitoSpyBean
    RabbitRoutingVerifier routingVerifier;

    @Test
    void 발행_실패시_재시도_정보를_저장하고_다른_이벤트는_발행한다() {

        Long productId = productService.registerProduct(
                "재시도 통합 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        Long firstOrderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        Long secondOrderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        OrderOutbox first = outboxRepository
                .findByOrderIdOrderByIdAsc(firstOrderId).get(0);

        OrderOutbox second = outboxRepository
                .findByOrderIdOrderByIdAsc(secondOrderId).get(0);

        // 첫 번째 이벤트만 발행 실패하도록 설정
        doAnswer(invocation -> {

            Message message = invocation.getArgument(2);

            if (first.getEventId().toString().equals(
                    message.getMessageProperties().getMessageId()
            )) {
                throw new IllegalStateException("RabbitMQ 발행 실패 재현");
            }

            return invocation.callRealMethod();

        }).when(routingVerifier).sendAndVerify(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.OUTBOX_ROUTING_KEY),
                any(Message.class)
        );

        // when
        scheduler.publishPendingEvents();

        // then: 첫 번째 이벤트는 재시도 예약
        OrderOutbox failed = outboxRepository
                .findById(first.getId()).orElseThrow();

        assertThat(failed.getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        assertThat(failed.getRetryCount())
                .isEqualTo(1);

        assertThat(failed.getNextRetryAt())
                .isAfter(LocalDateTime.now());

        // 두 번째 이벤트는 정상 발행
        OrderOutbox published = outboxRepository
                .findById(second.getId()).orElseThrow();

        assertThat(published.getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);

        Message received = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_QUEUE,
                5000
        );

        assertThat(received).isNotNull();

        assertThat(received.getMessageProperties().getMessageId())
                .isEqualTo(second.getEventId().toString());
    }
}