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
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {
        "stockflow.outbox.scheduling-enabled=false",
        "stockflow.outbox.consumer.enabled=false"
})
@Testcontainers
// 다중 스케줄러 동시 실행 통합 테스트
@org.junit.jupiter.api.Tag("integration")
class OrderOutboxMultiSchedulerTest {

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

    // @MockitoSpyBean을 사용해 첫 번째 발행만 일시 정지
    // 단순히 두 스레드를 동시에 시작하는 테스트보다,
    // 실제로 락이 겹친 상황을 확실히 재현할 수 있음
    @MockitoSpyBean
    RabbitRoutingVerifier routingVerifier;

    private OrderOutbox createPendingEvent(String name) {

        Long productId = productService.registerProduct(
                name,
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

    @Test
    void 두_스케줄러는_잠긴_이벤트를_건너뛰고_서로_다른_이벤트를_발행한다()
            throws Exception {

        // given
        OrderOutbox first = createPendingEvent("스케줄러 상품 A");
        OrderOutbox second = createPendingEvent("스케줄러 상품 B");

        CountDownLatch firstPublishing = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // 첫 번째 이벤트의 RabbitMQ 발행 직전에 멈추도록 설정
        doAnswer(invocation -> {

            Message message = invocation.getArgument(2);

            if (first.getEventId().toString().equals(
                    message.getMessageProperties().getMessageId()
            )) {
                firstPublishing.countDown();

                if (!releaseFirst.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "첫 번째 발행 대기 시간 초과"
                    );
                }
            }

            return invocation.callRealMethod();

        }).when(routingVerifier).sendAndVerify(
                eq(RabbitMqConfig.ORDER_EXCHANGE),
                eq(RabbitMqConfig.OUTBOX_ROUTING_KEY),
                any(Message.class)
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // 첫 번째 스케줄러 실행
            Future<?> firstTask = executor.submit(
                    scheduler::publishPendingEvents
            );

            // 첫 번째 이벤트를 잠그고 발행 직전까지 왔는지 확인
            assertThat(firstPublishing.await(10, TimeUnit.SECONDS))
                    .isTrue();

            // 첫 번째 이벤트가 잠겨 있는 동안
            // 두 번째 스케줄러 실행
            Future<?> secondTask = executor.submit(
                    scheduler::publishPendingEvents
            );

            // 두 번째 스케줄러는 잠긴 첫 번째 이벤트를 건너뛰고 종료
            secondTask.get(10, TimeUnit.SECONDS);

            // 첫 번째 트랜잭션은 아직 커밋 전
            assertThat(outboxRepository.findById(first.getId())
                    .orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PENDING);

            // 두 번째 이벤트는 이미 발행됨
            assertThat(outboxRepository.findById(second.getId())
                    .orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PUBLISHED);

            // 첫 번째 스케줄러 재개
            releaseFirst.countDown();
            firstTask.get(15, TimeUnit.SECONDS);

            // then: 두 이벤트 모두 발행 완료
            assertThat(outboxRepository.findById(first.getId())
                    .orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PUBLISHED);

            assertThat(outboxRepository.findById(second.getId())
                    .orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PUBLISHED);

            // RabbitMQ에 저장된 메시지 2건 확인
            Message message1 = rabbitTemplate.receive(
                    RabbitMqConfig.OUTBOX_QUEUE, 5000
            );

            Message message2 = rabbitTemplate.receive(
                    RabbitMqConfig.OUTBOX_QUEUE, 5000
            );

            assertThat(message1).isNotNull();
            assertThat(message2).isNotNull();

            Set<String> receivedIds = List.of(message1, message2)
                    .stream()
                    .map(m -> m.getMessageProperties().getMessageId())
                    .collect(Collectors.toSet());

            assertThat(receivedIds).containsExactlyInAnyOrder(
                    first.getEventId().toString(),
                    second.getEventId().toString()
            );

            // 중복 발행은 없어야 함
            assertThat(rabbitTemplate.receive(
                    RabbitMqConfig.OUTBOX_QUEUE, 500
            )).isNull();

        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }
}