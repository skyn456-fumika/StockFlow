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
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "stockflow.outbox.scheduling-enabled=false",
        "stockflow.outbox.consumer.enabled=false"
})
@Testcontainers
// Outbox 동시 발행 방지 테스트
class OrderOutboxConcurrencyTest {

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
    void 동일한_Outbox를_동시에_발행해도_한번만_성공한다()
            throws Exception {

        // given
        Long productId = productService.registerProduct(
                "Outbox 동시성 테스트 상품",
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

        int threadCount = 10;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> futures =
                    new java.util.ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executor.submit(() -> {

                    ready.countDown();
                    start.await();

                    return publisher.publishOne(outboxId);
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS))
                    .isTrue();

            // 10개 스레드 동시 시작
            start.countDown();

            int successCount = 0;
            int skippedCount = 0;

            for (Future<Boolean> future : futures) {

                boolean published =
                        future.get(30, TimeUnit.SECONDS);

                if (published) {
                    successCount++;
                } else {
                    skippedCount++;
                }
            }

            // 실제 발행 성공은 1회
            assertThat(successCount).isEqualTo(1);

            // 나머지는 이미 발행되어 건너뜀
            assertThat(skippedCount).isEqualTo(9);

            OrderOutbox saved = outboxRepository
                    .findById(outboxId)
                    .orElseThrow();

            assertThat(saved.getStatus())
                    .isEqualTo(OutboxStatus.PUBLISHED);

            // RabbitMQ에도 메시지가 한 건만 있어야 함
            Message received = rabbitTemplate.receive(
                    RabbitMqConfig.OUTBOX_QUEUE,
                    5000
            );

            assertThat(received).isNotNull();

            assertThat(received.getMessageProperties().getMessageId())
                    .isEqualTo(outbox.getEventId().toString());

            Message duplicate = rabbitTemplate.receive(
                    RabbitMqConfig.OUTBOX_QUEUE,
                    500
            );

            assertThat(duplicate).isNull();

        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}