package com.stockflow.domain.order.outbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "stockflow.outbox.scheduling-enabled=false",
        "stockflow.outbox.consumer.enabled=false"
})
@Testcontainers
class OrderOutboxBatchPublisherTest {

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
    OrderOutboxBatchPublisher batchPublisher;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    RabbitTemplate rabbitTemplate;

    private OrderOutbox createPendingEvent() {

        Long productId = productService.registerProduct(
                "Batch Publisher 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        return outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0);
    }

    // 각 테스트를 실행하기 전에
    // Outbox Queue에 남아 있는 메시지를 삭제하므로 테스트 간 간섭을 방지
    @BeforeEach
    void clearRabbitMqQueue() {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(RabbitMqConfig.OUTBOX_QUEUE);
            return null;
        });
    }

    @Test
    // 첫 번째 이벤트 잠금 → 두 번째 이벤트 발행
    void 잠긴_이벤트를_건너뛰고_다음_이벤트를_발행한다()
            throws Exception {

        OrderOutbox first = createPendingEvent();
        OrderOutbox second = createPendingEvent();

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService executor =
                Executors.newSingleThreadExecutor();

        try {
            Future<?> future = executor.submit(() -> {

                transactionTemplate.executeWithoutResult(status -> {

                    outboxRepository.findByIdForUpdate(first.getId())
                            .orElseThrow();

                    locked.countDown();

                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) {
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

            assertThat(locked.await(10, TimeUnit.SECONDS))
                    .isTrue();

            // 첫 번째 이벤트는 다른 트랜잭션이 잠금 중
            boolean published = batchPublisher.publishNext();

            assertThat(published).isTrue();

            assertThat(outboxRepository.findById(first.getId())
                    .orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PENDING);

            assertThat(outboxRepository.findById(second.getId())
                    .orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PUBLISHED);

            Message received = rabbitTemplate.receive(
                    RabbitMqConfig.OUTBOX_QUEUE,
                    5000
            );

            assertThat(received).isNotNull();

            assertThat(received.getMessageProperties().getMessageId())
                    .isEqualTo(second.getEventId().toString());

            // 잠금 해제 후 첫 번째 이벤트도 발행 가능
            release.countDown();
            future.get(10, TimeUnit.SECONDS);

            assertThat(batchPublisher.publishNext()).isTrue();

            assertThat(outboxRepository.findById(first.getId())
                    .orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PUBLISHED);

        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    // 발행할 PENDING 이벤트 없음 → false 반환
    void 발행할_PENDING_이벤트가_없으면_false를_반환한다() {

        // 테스트 컨테이너 내 다른 테스트 데이터가 남을 수 있으므로
        // PENDING 이벤트가 있으면 먼저 모두 발행
        while (batchPublisher.publishNext()) {
            // 모든 PENDING 이벤트 처리
        }

        assertThat(batchPublisher.publishNext()).isFalse();
    }

    @Test
    void 재시도_대기중인_이벤트를_건너뛰고_다음_이벤트를_발행한다() {

        // given
        OrderOutbox first = createPendingEvent();
        OrderOutbox second = createPendingEvent();

        // 첫 번째 이벤트는 아직 재시도할 수 없도록 설정
        transactionTemplate.executeWithoutResult(status -> {
            OrderOutbox outbox = outboxRepository
                    .findByIdForUpdate(first.getId())
                    .orElseThrow();

            outbox.scheduleRetry(
                    LocalDateTime.now().plusMinutes(10)
            );
        });

        // when
        boolean published = batchPublisher.publishNext();

        // then
        assertThat(published).isTrue();

        assertThat(outboxRepository.findById(first.getId())
                .orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PENDING);

        assertThat(outboxRepository.findById(second.getId())
                .orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);

        // 첫 번째 이벤트만 남았지만 아직 재시도 시각 전
        assertThat(batchPublisher.publishNext()).isFalse();

        // 재시도 시각이 지난 상황 재현
        transactionTemplate.executeWithoutResult(status -> {
            OrderOutbox outbox = outboxRepository
                    .findByIdForUpdate(first.getId())
                    .orElseThrow();

            outbox.scheduleRetry(
                    LocalDateTime.now().minusMinutes(10)
            );
        });

        // 재시도 가능해진 첫 번째 이벤트 발행
        assertThat(batchPublisher.publishNext()).isTrue();

        assertThat(outboxRepository.findById(first.getId())
                .orElseThrow().getStatus())
                .isEqualTo(OutboxStatus.PUBLISHED);
    }
}