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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "stockflow.outbox.scheduling-enabled=false",
        "stockflow.outbox.consumer.enabled=false"
})
@Testcontainers
// 재시도 시간이 지난 이벤트의 자동 발행 복구 테스트
class OrderOutboxRetryRecoveryTest {

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
    void 발행_실패후_재시도_시간이_지나면_정상_발행된다()
            throws Exception {

        // given
        Long productId = productService.registerProduct(
                "Outbox 재시도 복구 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        OrderOutbox outbox = outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0);

        Long outboxId = outbox.getId();

        AtomicInteger attempts = new AtomicInteger();

        // 첫 발행만 실패, 두 번째부터는 실제 RabbitMQ 발행
        doAnswer(invocation -> {

            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException(
                        "일시적인 RabbitMQ 장애"
                );
            }

            return invocation.callRealMethod();

        }).when(routingVerifier).sendAndVerify(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.OUTBOX_ROUTING_KEY),
                any(Message.class)
        );

        // when 1: 최초 발행 실패
        scheduler.publishPendingEvents();

        OrderOutbox failed = outboxRepository
                .findById(outboxId)
                .orElseThrow();

        assertThat(failed.getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        assertThat(failed.getRetryCount())
                .isEqualTo(1);

        assertThat(failed.getNextRetryAt())
                .isAfter(LocalDateTime.now());

        // when 2: 재시도 시각 전에는 발행하지 않음
        scheduler.publishPendingEvents();

        assertThat(attempts.get()).isEqualTo(1);

        // when 3: 실제 재시도 시각까지 스케줄러 반복 실행
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(12);

        OrderOutbox recovered = failed;

        while (System.nanoTime() < deadline) {

            scheduler.publishPendingEvents();

            recovered = outboxRepository
                    .findById(outboxId)
                    .orElseThrow();

            if (recovered.getStatus() == OutboxStatus.PUBLISHED) {
                break;
            }

            Thread.sleep(200);
        }

        // then: 재시도 후 정상 발행
        assertThat(recovered.getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);

        assertThat(recovered.getRetryCount())
                .isEqualTo(1);

        assertThat(recovered.getNextRetryAt())
                .isNull();

        assertThat(attempts.get()).isEqualTo(2);

        // RabbitMQ 실제 메시지 확인
        Message received = rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_QUEUE,
                5000
        );

        assertThat(received).isNotNull();

        assertThat(received.getMessageProperties().getMessageId())
                .isEqualTo(outbox.getEventId().toString());

        // 중복 발행 없음
        assertThat(rabbitTemplate.receive(
                RabbitMqConfig.OUTBOX_QUEUE,
                500
        )).isNull();
    }
}