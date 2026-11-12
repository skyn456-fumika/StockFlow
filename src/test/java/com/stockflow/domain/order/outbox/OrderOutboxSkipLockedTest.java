package com.stockflow.domain.order.outbox;

import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;

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
class OrderOutboxSkipLockedTest {

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
    // 각 스레드가 별도 트랜잭션을 갖도록 하기 위해서 사용
    TransactionTemplate transactionTemplate;

    @Test
    void 다른_트랜잭션이_잠근_Outbox는_대기하지_않고_건너뛴다()
            throws Exception {

        // given
        Long productId = productService.registerProduct(
                "SKIP LOCKED 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        Long outboxId = outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0)
                .getId();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        try {
            // 첫 번째 트랜잭션에서 Outbox 잠금 유지
            Future<?> first = executor.submit(() -> {

                transactionTemplate.executeWithoutResult(status -> {

                    OrderOutbox outbox = outboxRepository
                            .findByIdForUpdate(outboxId)
                            .orElseThrow();

                    assertThat(outbox.getStatus())
                            .isEqualTo(OutboxStatus.PENDING);

                    locked.countDown();

                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "잠금 해제 대기 시간 초과"
                            );
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                });
            });

            // 첫 번째 트랜잭션이 락을 획득했는지 확인
            assertThat(locked.await(10, TimeUnit.SECONDS))
                    .isTrue();

            // when: 다른 트랜잭션에서 SKIP LOCKED 조회
            List<OrderOutbox> available =
                    transactionTemplate.execute(status ->
                            outboxRepository.findPendingBatchSkipLocked()
                    );

            // then: 잠긴 이벤트는 조회되지 않음
            assertThat(available).isEmpty();

            // 첫 번째 트랜잭션 종료
            release.countDown();
            first.get(10, TimeUnit.SECONDS);

            // 락이 해제된 후에는 다시 조회 가능
            List<OrderOutbox> afterRelease =
                    transactionTemplate.execute(status ->
                            outboxRepository.findPendingBatchSkipLocked()
                    );

            assertThat(afterRelease)
                    .extracting(OrderOutbox::getId)
                    .contains(outboxId);

        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}