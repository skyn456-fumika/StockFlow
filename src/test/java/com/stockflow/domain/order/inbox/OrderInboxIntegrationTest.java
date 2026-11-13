package com.stockflow.domain.order.inbox;

import com.stockflow.config.RabbitMqConfig;
import com.stockflow.domain.order.OrderService;
import com.stockflow.domain.order.dto.OrderCreateItem;
import com.stockflow.domain.order.outbox.OrderOutbox;
import com.stockflow.domain.order.outbox.OrderOutboxMessage;
import com.stockflow.domain.order.outbox.OrderOutboxPublisher;
import com.stockflow.domain.order.outbox.OrderOutboxRepository;
import com.stockflow.domain.product.ProductService;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "stockflow.outbox.consumer.enabled=true"
})
@Testcontainers
@org.junit.jupiter.api.Tag("integration")
class OrderInboxIntegrationTest {

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
    OrderInboxService inboxService;

    @Autowired
    ProcessedOrderEventRepository processedRepository;

    @Autowired
    RabbitTemplate rabbitTemplate;

    // 실제 Consumer 동작을 유지하면서 메서드 호출 횟수를 검증하기 위해 사용
    @MockitoSpyBean
    OrderOutboxConsumer consumer;

    @Test
    void 처음_처리하는_이벤트는_Inbox에_저장된다() {

        Long productId = productService.registerProduct(
                "Inbox 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        OrderOutboxMessage event = new OrderOutboxMessage(
                UUID.randomUUID(),
                orderId,
                "ORDER_CREATED",
                LocalDateTime.now()
        );

        boolean processed = inboxService.process(event);

        assertThat(processed).isTrue();

        assertThat(processedRepository.existsById(event.eventId()))
                .isTrue();
    }

    @Test
    void 동일한_이벤트를_두번_처리해도_한번만_저장된다() {

        Long productId = productService.registerProduct(
                "Inbox 중복 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        OrderOutboxMessage event = new OrderOutboxMessage(
                UUID.randomUUID(),
                orderId,
                "ORDER_CREATED",
                LocalDateTime.now()
        );

        long beforeCount = processedRepository.count();

        boolean first = inboxService.process(event);
        boolean second = inboxService.process(event);

        assertThat(first).isTrue();
        assertThat(second).isFalse();

        assertThat(processedRepository.count())
                .isEqualTo(beforeCount + 1);
    }

    @Test
    void RabbitMQ_이벤트를_Consumer가_수신해_Inbox에_기록한다()
            throws Exception {

        Long productId = productService.registerProduct(
                "Consumer 통합 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 2))
        );

        OrderOutbox outbox = outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0);

        UUID eventId = outbox.getEventId();

        assertThat(processedRepository.existsById(eventId))
                .isFalse();

        // RabbitMQ 발행
        assertThat(publisher.publishOne(outbox.getId()))
                .isTrue();

        // Consumer의 비동기 처리 결과 대기
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);

        boolean processed = false;

        while (System.nanoTime() < deadline) {
            if (processedRepository.existsById(eventId)) {
                processed = true;
                break;
            }

            Thread.sleep(100);
        }

        assertThat(processed).isTrue();

        ProcessedOrderEvent saved = processedRepository
                .findById(eventId)
                .orElseThrow();

        assertThat(saved.getOrderId()).isEqualTo(orderId);
        assertThat(saved.getEventType())
                .isEqualTo("ORDER_CREATED");
    }

    @Test
    void RabbitMQ에서_동일한_이벤트를_두번_수신해도_Inbox에는_한번만_기록된다()
            throws Exception {

        // given: 상품 및 주문 생성
        Long productId = productService.registerProduct(
                "RabbitMQ 중복 메시지 테스트 상품",
                new BigDecimal("10000"),
                10
        );

        Long orderId = orderService.createOrder(
                List.of(new OrderCreateItem(productId, 1))
        );

        OrderOutbox outbox = outboxRepository
                .findByOrderIdOrderByIdAsc(orderId)
                .get(0);

        UUID eventId = outbox.getEventId();

        long beforeCount = processedRepository.count();

        // 같은 eventId, 같은 payload를 가진 메시지 생성
        Message message = MessageBuilder
                .withBody(
                        outbox.getPayload()
                                .getBytes(StandardCharsets.UTF_8)
                )
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setMessageId(eventId.toString())
                .build();

        // when: 동일한 이벤트를 RabbitMQ에 두 번 발행
        rabbitTemplate.send(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.OUTBOX_ROUTING_KEY,
                message
        );

        rabbitTemplate.send(
                RabbitMqConfig.ORDER_EXCHANGE,
                RabbitMqConfig.OUTBOX_ROUTING_KEY,
                message
        );

        // then: Consumer가 동일 eventId를 가진 메시지 2건 수신
        verify(consumer, timeout(10000).times(2))
                .consume(argThat(received ->
                        received != null
                                && eventId.toString().equals(
                                received.getMessageProperties().getMessageId()
                        )
                ));

        // Consumer의 비동기 DB 처리 완료 확인
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline
                && !processedRepository.existsById(eventId)) {
            Thread.sleep(100);
        }

        assertThat(processedRepository.existsById(eventId))
                .isTrue();

        // 동일 이벤트의 Inbox 기록은 한 건만 증가
        assertThat(processedRepository.count())
                .isEqualTo(beforeCount + 1);

        ProcessedOrderEvent saved = processedRepository
                .findById(eventId)
                .orElseThrow();

        assertThat(saved.getOrderId()).isEqualTo(orderId);
        assertThat(saved.getEventType()).isEqualTo("ORDER_CREATED");
    }
}